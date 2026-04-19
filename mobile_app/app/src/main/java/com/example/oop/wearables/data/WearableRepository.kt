package com.example.oop.wearables.data

import android.content.Context
import com.example.oop.wearables.WearablesConfig
import com.example.oop.wearables.model.SourceStatus
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import com.example.oop.wearables.source.WearableDataSource
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface WearableRepository {

    val vitals: SharedFlow<VitalSample>

    val sources: StateFlow<Map<WearableSource, SourceStatus>>

    val isStreaming: StateFlow<Boolean>

    val enabledSources: StateFlow<Set<WearableSource>>

    suspend fun window(
        from: Instant,
        to: Instant,
        types: Set<VitalType> = VitalType.allClinical,
    ): List<VitalSample>

    fun latest(type: VitalType): VitalSample?

    fun latestSnapshot(): Map<VitalType, VitalSample>

    suspend fun startStreaming()

    suspend fun stopStreaming()

    suspend fun setSourceEnabled(source: WearableSource, enabled: Boolean)

    suspend fun missingHealthConnectPermissions(): Set<String>

    suspend fun reinitializeSources()
}

internal class WearableRepositoryImpl(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val database: WearableDatabase,
    sources: List<WearableDataSource>,
    initialEnabled: Set<WearableSource>,
    private val healthConnectPermissionsProvider: suspend () -> Set<String>,
) : WearableRepository {

    private val dao = database.vitalDao()

    private val sourcesById: Map<WearableSource, WearableDataSource> =
        sources.associateBy { it.id }

    private val _vitals = MutableSharedFlow<VitalSample>(
        replay = 0,
        extraBufferCapacity = WearablesConfig.sharedFlowBufferCapacity,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private val _sources = MutableStateFlow<Map<WearableSource, SourceStatus>>(
        sources.associate { it.id to SourceStatus.Uninitialized },
    )

    private val _isStreaming = MutableStateFlow(false)

    private val _enabledSources = MutableStateFlow(initialEnabled)

    private val latestCache = ConcurrentHashMap<VitalType, VitalSample>()

    private val ingestionJobs = mutableListOf<Job>()
    private val ingestionLock = Mutex()

    private val persistChannel = Channel<VitalSample>(Channel.UNLIMITED)

    override val vitals: SharedFlow<VitalSample> = _vitals.asSharedFlow()

    override val sources: StateFlow<Map<WearableSource, SourceStatus>> =
        _sources.asStateFlow()

    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    override val enabledSources: StateFlow<Set<WearableSource>> =
        _enabledSources.asStateFlow()

    init {
        sourcesById.values.forEach { source ->
            scope.launch {
                source.status.collect { status ->
                    _sources.update { current -> current + (source.id to status) }
                }
            }
        }
        scope.launch { runPersistBatcher() }
    }

    override suspend fun window(
        from: Instant,
        to: Instant,
        types: Set<VitalType>,
    ): List<VitalSample> {
        if (types.isEmpty()) return emptyList()
        val fromMs = from.toEpochMilli()
        val toMs = to.toEpochMilli()
        if (toMs < fromMs) return emptyList()
        val codes = types.map { it.code }
        return dao.window(fromMs = fromMs, toMs = toMs, typeCodes = codes)
            .mapNotNull(VitalMapping::fromEntity)
    }

    override fun latest(type: VitalType): VitalSample? = latestCache[type]

    override fun latestSnapshot(): Map<VitalType, VitalSample> = latestCache.toMap()

    override suspend fun startStreaming() {
        if (_isStreaming.value) return
        com.example.oop.wearables.service.WearableStreamingService.start(appContext)
        _isStreaming.value = true
    }

    override suspend fun stopStreaming() {
        if (!_isStreaming.value) return
        com.example.oop.wearables.service.WearableStreamingService.stop(appContext)
        _isStreaming.value = false
    }

    override suspend fun setSourceEnabled(source: WearableSource, enabled: Boolean) {
        val next = if (enabled) _enabledSources.value + source else _enabledSources.value - source
        if (next == _enabledSources.value) return
        _enabledSources.value = next
        if (_isStreaming.value) {
            endIngestion()
            beginIngestion()
        }
    }

    override suspend fun missingHealthConnectPermissions(): Set<String> =
        healthConnectPermissionsProvider()

    override suspend fun reinitializeSources() {
        sourcesById.values.forEach { source ->
            runCatching { source.initialize(appContext) }
        }
    }

    suspend fun beginIngestion() {
        ingestionLock.withLock {
            if (ingestionJobs.isNotEmpty()) return
            val activeSources = sourcesById.values.filter { it.id in _enabledSources.value }
            activeSources.forEach { source ->
                ingestionJobs += scope.launch { runCatching { source.initialize(appContext) } }
                ingestionJobs += scope.launch {
                    source.vitals().collect { sample -> ingest(sample) }
                }
            }
        }
    }

    suspend fun endIngestion() {
        ingestionLock.withLock {
            ingestionJobs.forEach { it.cancel() }
            ingestionJobs.clear()
            sourcesById.values.forEach { runCatching { it.close() } }
        }
    }

    private suspend fun ingest(sample: VitalSample) {
        latestCache[sample.type] = sample
        _vitals.tryEmit(sample)
        persistChannel.send(sample)
    }

    private suspend fun runPersistBatcher() {
        val buffer = ArrayList<VitalSample>(WearablesConfig.persistBatchMaxSize)
        while (true) {
            val first = persistChannel.receive()
            buffer += first
            val deadlineMs = WearablesConfig.persistBatchMaxLatency.inWholeMilliseconds
            while (buffer.size < WearablesConfig.persistBatchMaxSize) {
                val next = withTimeoutOrNull(deadlineMs) { persistChannel.receive() } ?: break
                buffer += next
            }
            val snapshot = buffer.map(VitalMapping::toEntity)
            buffer.clear()
            runCatching { dao.insertAll(snapshot) }
        }
    }
}
