package com.example.oop.wearables.source

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.example.oop.wearables.WearablesConfig
import com.example.oop.wearables.model.SourceStatus
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.WearableSource
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow

class HealthConnectSource(private val appContext: Context) : WearableDataSource {

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Uninitialized)

    override val id: WearableSource = WearableSource.HEALTH_CONNECT

    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    @Volatile
    private var client: HealthConnectClient? = null

    override suspend fun initialize(context: Context) {
        when (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                client = null
                _status.value = SourceStatus.Unsupported
                return
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                client = null
                _status.value = SourceStatus.NotInstalled
                return
            }
        }

        val resolved = runCatching { HealthConnectClient.getOrCreate(context) }
            .getOrElse { throwable ->
                Log.w(TAG, "HealthConnectClient.getOrCreate failed", throwable)
                _status.value = SourceStatus.Error(
                    message = throwable.message ?: "Failed to create Health Connect client",
                    cause = throwable,
                )
                return
            }
        client = resolved

        val missing = computeMissing(resolved)
        _status.value = if (missing.isEmpty()) {
            SourceStatus.Ready
        } else {
            SourceStatus.PermissionRequired(missing)
        }
    }

    suspend fun missingPermissions(): Set<String> {
        val cli = client ?: run {
            initialize(appContext)
            client ?: return ALL_READ_PERMISSIONS
        }
        return computeMissing(cli)
    }

    override fun vitals(): Flow<VitalSample> = channelFlow {
        val cli = client ?: run {
            initialize(appContext)
            client
        }
        if (cli == null) {
            return@channelFlow
        }

        val granted = runCatching { cli.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        val activeRecordTypes = RECORD_PERMISSION_PAIRS
            .filter { it.permission in granted }
            .map { it.recordType }
            .toSet()

        if (activeRecordTypes.isEmpty()) {
            _status.value = SourceStatus.PermissionRequired(ALL_READ_PERMISSIONS - granted)
            return@channelFlow
        }
        _status.value = SourceStatus.Ready

        bootstrapWindow(cli, activeRecordTypes, recent = false)

        var token = runCatching {
            cli.getChangesToken(ChangesTokenRequest(recordTypes = activeRecordTypes))
        }.getOrElse { throwable ->
            Log.w(TAG, "getChangesToken failed", throwable)
            _status.value = SourceStatus.Error(
                message = throwable.message ?: "Health Connect changes token failed",
                cause = throwable,
            )
            return@channelFlow
        }

        while (true) {
            delay(WearablesConfig.healthConnectPollInterval.inWholeMilliseconds)
            val response = runCatching { cli.getChanges(token) }.getOrElse { throwable ->
                Log.w(TAG, "getChanges failed; retrying", throwable)
                null
            } ?: continue

            if (response.changesTokenExpired) {
                Log.i(TAG, "Changes token expired; rebootstrapping")
                bootstrapWindow(cli, activeRecordTypes, recent = true)
                token = runCatching {
                    cli.getChangesToken(ChangesTokenRequest(recordTypes = activeRecordTypes))
                }.getOrElse { token }
                continue
            }

            response.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> {
                        HealthConnectMappers.toSamples(change.record).forEach { send(it) }
                    }

                    is DeletionChange -> Unit
                }
            }
            token = response.nextChangesToken
        }
    }

    override suspend fun close() {
        client = null
        _status.value = SourceStatus.Uninitialized
    }

    private suspend fun ProducerScope<VitalSample>.bootstrapWindow(
        cli: HealthConnectClient,
        activeRecordTypes: Set<KClass<out Record>>,
        recent: Boolean,
    ) {
        val endTime = Instant.now()
        val windowSeconds = if (recent) 60L else WearablesConfig.bootstrapReadWindow.inWholeSeconds
        val startTime = endTime.minusSeconds(windowSeconds)
        val range = TimeRangeFilter.between(startTime, endTime)

        if (HeartRateRecord::class in activeRecordTypes) {
            readAndEmit(cli, HeartRateRecord::class, range)
        }
        if (HeartRateVariabilityRmssdRecord::class in activeRecordTypes) {
            readAndEmit(cli, HeartRateVariabilityRmssdRecord::class, range)
        }
        if (OxygenSaturationRecord::class in activeRecordTypes) {
            readAndEmit(cli, OxygenSaturationRecord::class, range)
        }
        if (BloodPressureRecord::class in activeRecordTypes) {
            readAndEmit(cli, BloodPressureRecord::class, range)
        }
        if (BodyTemperatureRecord::class in activeRecordTypes) {
            readAndEmit(cli, BodyTemperatureRecord::class, range)
        }
        if (SkinTemperatureRecord::class in activeRecordTypes) {
            readAndEmit(cli, SkinTemperatureRecord::class, range)
        }
        if (RespiratoryRateRecord::class in activeRecordTypes) {
            readAndEmit(cli, RespiratoryRateRecord::class, range)
        }
    }

    private suspend fun <T : Record> ProducerScope<VitalSample>.readAndEmit(
        cli: HealthConnectClient,
        recordType: KClass<T>,
        range: TimeRangeFilter,
    ) {
        val request = ReadRecordsRequest(recordType = recordType, timeRangeFilter = range)
        val records = runCatching { cli.readRecords(request) }.getOrNull()?.records
            ?: return
        records.forEach { record ->
            HealthConnectMappers.toSamples(record).forEach { send(it) }
        }
    }

    private suspend fun computeMissing(cli: HealthConnectClient): Set<String> {
        val granted = runCatching { cli.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        return ALL_READ_PERMISSIONS - granted
    }

    private data class RecordPermissionPair(
        val recordType: KClass<out Record>,
        val permission: String,
    )

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private const val TAG = "HealthConnectSource"

        private val RECORD_PERMISSION_PAIRS = listOf(
            RecordPermissionPair(
                recordType = HeartRateRecord::class,
                permission = HealthPermission.getReadPermission(HeartRateRecord::class),
            ),
            RecordPermissionPair(
                recordType = HeartRateVariabilityRmssdRecord::class,
                permission = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            ),
            RecordPermissionPair(
                recordType = OxygenSaturationRecord::class,
                permission = HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            ),
            RecordPermissionPair(
                recordType = BloodPressureRecord::class,
                permission = HealthPermission.getReadPermission(BloodPressureRecord::class),
            ),
            RecordPermissionPair(
                recordType = BodyTemperatureRecord::class,
                permission = HealthPermission.getReadPermission(BodyTemperatureRecord::class),
            ),
            RecordPermissionPair(
                recordType = SkinTemperatureRecord::class,
                permission = HealthPermission.getReadPermission(SkinTemperatureRecord::class),
            ),
            RecordPermissionPair(
                recordType = RespiratoryRateRecord::class,
                permission = HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            ),
        )

        val ALL_READ_PERMISSIONS: Set<String> =
            RECORD_PERMISSION_PAIRS.map { it.permission }.toSet()
    }
}
