package com.example.oop.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.oop.audio.AudioCapture
import com.example.oop.audio.BluetoothScoRouter
import com.example.oop.audio.UtteranceChunker
import com.example.oop.chat.mvi.ChatEffect
import com.example.oop.chat.mvi.ChatIntent
import com.example.oop.chat.mvi.ChatUiState
import com.example.oop.chat.mvi.ClassifiedCaption
import com.example.oop.chat.mvi.Status
import com.example.oop.chat.mvi.TranscriptCaptureThumbnail
import com.example.oop.llm.DownloadState
import com.example.oop.llm.LiteRtLmEngine
import com.example.oop.llm.ModelDownloader
import com.example.oop.llm.ModelNotFoundException
import com.example.oop.llm.ModelPaths
import com.example.oop.llm.TokenStore
import com.example.oop.llm.TurnEvent
import com.example.oop.llm.tools.AisToolExecutor
import com.example.oop.llm.tools.IcdToolExecutor
import com.example.oop.llm.tools.SnomedToolExecutor
import com.example.oop.llm.tools.TriggerToolExecutor
import com.example.oop.ontology.Designation
import com.example.oop.ontology.InstallState
import com.example.oop.ontology.OntologyIndex
import com.example.oop.ontology.OntologyInstaller
import com.example.oop.ontology.TriggerCategory
import com.example.oop.ontology.TriggerIndex
import com.example.oop.ontology.TriggerOntology
import com.example.oop.stream.TriggerContext
import com.example.oop.stream.StreamViewModel
import com.example.oop.transcription.TranscriptUtterance
import com.example.oop.transcription.TranscriptionEvent
import com.example.oop.transcription.TranscriptionService
import com.example.oop.transcription.TranscriptionStatus
import com.example.oop.whisper.WhisperDownloadState
import com.example.oop.whisper.WhisperEngine
import com.example.oop.whisper.WhisperModelDownloader
import com.example.oop.whisper.WhisperPaths
import com.meta.wearable.dat.camera.types.StreamSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.EnumMap

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val engine = LiteRtLmEngine(appContext)
    private val whisperEngine = WhisperEngine(appContext)
    private val whisperDownloader = WhisperModelDownloader(appContext)
    private val transcriptionService =
        TranscriptionService(
            router = BluetoothScoRouter(appContext),
            capture = AudioCapture(),
            chunker = UtteranceChunker(),
            engine = whisperEngine,
        )
    private val tokenStore = TokenStore(appContext)
    private val downloader = ModelDownloader(appContext, tokenStore)
    private val installer = OntologyInstaller(appContext)
    private val ontologyIndex = OntologyIndex(appContext)
    private val snomedExecutor = SnomedToolExecutor(ontologyIndex)
    private val icdExecutor = IcdToolExecutor(ontologyIndex)
    private val aisExecutor = AisToolExecutor(ontologyIndex)
    private val triggerIndex = TriggerIndex(TriggerOntology.load(appContext))
    private val triggerExecutor = TriggerToolExecutor(triggerIndex)
    private val _state = MutableStateFlow(ChatUiState())
    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)

    private var nextId = 0L
    private var isLoadingModel = false
    private var currentDownloadJob: Job? = null
    private var classifierJob: Job? = null
    private var transcriptionJob: Job? = null
    private var streamStateJob: Job? = null
    private var frameSource: StreamViewModel? = null
    private var scanCursor = -1L
    private val cooldowns = EnumMap<TriggerCategory, Long>(TriggerCategory::class.java)

    val state = _state.asStateFlow()
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            ChatIntent.LoadModel -> loadModel()
            ChatIntent.StartDownload -> startDownload()
            is ChatIntent.SubmitToken -> submitToken(intent.value)
            ChatIntent.CancelDownload -> cancelDownload()
            is ChatIntent.ToggleClassification -> toggleClassification(intent.enabled)
            ChatIntent.StartTranscription -> startTranscription()
            ChatIntent.StopTranscription -> stopTranscription()
            ChatIntent.CapturePhoto -> capturePhoto()
            ChatIntent.ResetConversation -> resetConversation()
            ChatIntent.DismissError -> dismissError()
        }
    }

    fun bindFrameSource(stream: StreamViewModel) {
        if (frameSource === stream && streamStateJob?.isActive == true) {
            return
        }

        frameSource = stream
        streamStateJob?.cancel()
        streamStateJob =
            viewModelScope.launch {
                stream.uiState
                    .map { it.streamSessionState }
                    .distinctUntilChanged()
                    .collect { sessionState ->
                        Log.i(AUDIO_TAG, "bindFrameSource: streamSessionState=$sessionState")
                        if (sessionState == StreamSessionState.STREAMING) {
                            startTranscription()
                        } else {
                            stopTranscription()
                        }
                    }
            }
    }

    private fun loadModel() {
        if (engine.isInitialized || isLoadingModel) {
            return
        }

        viewModelScope.launch {
            var installFailed = false
            installer.ensureInstalled().collect { step ->
                when (step) {
                    is InstallState.Copying -> {
                        _state.update { current ->
                            current.copy(
                                status = Status.OntologyInstalling(
                                    file = step.file,
                                    bytes = step.bytes,
                                    total = step.total,
                                ),
                            )
                        }
                    }

                    is InstallState.Failed -> {
                        installFailed = true
                        _state.update { current ->
                            current.copy(status = Status.OntologyFailed(step.message))
                        }
                    }

                    InstallState.Done -> Unit
                }
            }
            if (installFailed) {
                return@launch
            }

            val indexLoaded = runCatching { ontologyIndex.load() }
            if (indexLoaded.isFailure) {
                val message = indexLoaded.exceptionOrNull()?.message ?: "Ontology index failed to load"
                _state.update { current ->
                    current.copy(status = Status.OntologyFailed(message))
                }
                return@launch
            }

            val existingModel = ModelPaths.resolveExisting(appContext)
            if (existingModel == null) {
                val token = tokenStore.get()
                _state.update { current ->
                    current.copy(
                        status = if (token == null) {
                            Status.NeedsToken
                        } else {
                            Status.Downloading(bytesDownloaded = 0L, totalBytes = -1L)
                        },
                    )
                }
                if (token != null && currentDownloadJob?.isActive != true) {
                    startDownload()
                }
                return@launch
            }

            isLoadingModel = true
            _state.update { it.copy(status = Status.Loading) }
            runCatching { engine.initialize() }
                .onSuccess {
                    _state.update { current ->
                        current.copy(status = Status.Ready(engine.isGpu))
                    }
                }
                .onFailure { throwable ->
                    _state.update { current ->
                        current.copy(
                            status = when (throwable) {
                                is ModelNotFoundException -> Status.ModelMissing
                                else -> Status.Error(throwable.message ?: "Model initialization failed")
                            },
                        )
                    }
                    if (throwable !is ModelNotFoundException) {
                        _effects.trySend(
                            ChatEffect.ShowError(throwable.message ?: "Model initialization failed"),
                        )
                    }
                }
            isLoadingModel = false
        }
    }

    private fun submitToken(rawValue: String) {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank()) {
            return
        }
        tokenStore.save(trimmed)
        startDownload()
    }

    private fun startDownload() {
        if (currentDownloadJob?.isActive == true) {
            return
        }

        val token = tokenStore.get()
        if (token == null) {
            _state.update { current -> current.copy(status = Status.NeedsToken) }
            return
        }

        currentDownloadJob = viewModelScope.launch {
            _state.update { current ->
                current.copy(status = Status.Downloading(bytesDownloaded = 0L, totalBytes = -1L))
            }

            try {
                downloader.download().collect { downloadState ->
                    when (downloadState) {
                        is DownloadState.InProgress -> {
                            _state.update { current ->
                                current.copy(
                                    status = Status.Downloading(
                                        bytesDownloaded = downloadState.bytesDownloaded,
                                        totalBytes = downloadState.totalBytes,
                                    ),
                                )
                            }
                        }

                        DownloadState.Success -> loadModel()

                        is DownloadState.Failed -> {
                            _state.update { current ->
                                current.copy(
                                    status = when (downloadState.reason) {
                                        DownloadState.Reason.UNAUTHORIZED -> Status.NeedsToken
                                        DownloadState.Reason.CANCELLED -> Status.ModelMissing
                                        else -> Status.DownloadFailed(
                                            message = downloadState.message,
                                            canRetry = downloadState.reason != DownloadState.Reason.STORAGE,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            } finally {
                currentDownloadJob = null
            }
        }
    }

    private fun cancelDownload() {
        classifierJob?.cancel()
        downloader.cancel()
        downloader.clearPartial()
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        _state.update { current ->
            current.copy(
                isClassificationEnabled = false,
                caption = null,
                status = if (tokenStore.get() == null) {
                    Status.NeedsToken
                } else {
                    Status.ModelMissing
                },
            )
        }
    }

    private fun toggleClassification(enabled: Boolean) {
        if (enabled) {
            if (!engine.isInitialized) {
                _effects.trySend(ChatEffect.ShowError("Gemma is not ready yet."))
                return
            }

            val stream = frameSource ?: run {
                _effects.trySend(ChatEffect.ShowError("Camera stream unavailable."))
                return
            }

            if (classifierJob?.isActive == true) {
                return
            }

            scanCursor = _state.value.transcript.lastOrNull()?.id ?: -1L
            cooldowns.clear()
            _state.update { current ->
                current.copy(
                    isClassificationEnabled = true,
                    status = Status.Classifying,
                    caption = null,
                )
            }
            classifierJob = viewModelScope.launch(Dispatchers.Default) { triggerScanLoop(stream) }
            return
        }

        classifierJob?.cancel()
        classifierJob = null
        cooldowns.clear()
        _state.update { current ->
            current.copy(
                isClassificationEnabled = false,
                caption = null,
                status = if (engine.isInitialized) {
                    Status.Ready(engine.isGpu)
                } else {
                    current.status
                },
            )
        }
    }

    private fun startTranscription() {
        if (transcriptionJob?.isActive == true) {
            Log.d(AUDIO_TAG, "startTranscription: already active, skipping")
            return
        }
        Log.i(AUDIO_TAG, "startTranscription: launching")

        transcriptionJob =
            viewModelScope.launch {
                _state.update { current ->
                    current.copy(
                        transcriptionStatus = TranscriptionStatus.Routing,
                        livePartial = "",
                    )
                }

                val initError =
                    runCatching {
                        Log.d(AUDIO_TAG, "startTranscription: ensureWhisperReady")
                        ensureWhisperReady()
                    }.exceptionOrNull()
                if (initError != null) {
                    Log.e(AUDIO_TAG, "startTranscription: whisper init failed", initError)
                    _state.update { current ->
                        current.copy(transcriptionStatus = TranscriptionStatus.Error)
                    }
                    _effects.trySend(
                        ChatEffect.ShowError(
                            initError.message ?: "Unable to initialize Whisper transcription.",
                        ),
                    )
                    transcriptionJob = null
                    return@launch
                }

                transcriptionLoop()
            }
    }

    private fun stopTranscription() {
        if (transcriptionJob != null) {
            Log.i(AUDIO_TAG, "stopTranscription: cancelling transcription job")
        }
        transcriptionJob?.cancel()
        whisperDownloader.cancel()
        whisperDownloader.clearPartial()
        transcriptionJob = null
        _state.update { current ->
            current.copy(
                livePartial = "",
                transcriptionStatus = TranscriptionStatus.Idle,
            )
        }
    }

    private suspend fun transcriptionLoop() {
        Log.i(AUDIO_TAG, "transcriptionLoop: collecting service events")
        try {
            transcriptionService.run().collect { event ->
                when (event) {
                    is TranscriptionEvent.Partial -> {
                        Log.d(AUDIO_TAG, "event PARTIAL \"${event.text.take(80)}\"")
                        _state.update { current ->
                            current.copy(livePartial = event.text)
                        }
                    }

                    is TranscriptionEvent.Final -> {
                        Log.i(
                            AUDIO_TAG,
                            "event FINAL ${event.utterance.startMs}..${event.utterance.endMs}ms" +
                                " \"${event.utterance.text.take(120)}\"",
                        )
                        val utterance =
                            event.utterance.copy(
                                id = nextId++,
                            )
                        _state.update { current ->
                            current.copy(
                                transcript = current.transcript + utterance,
                                livePartial = "",
                            )
                        }
                        _effects.trySend(
                            ChatEffect.ScrollToBottom(_state.value.transcript.lastIndex),
                        )
                    }

                    is TranscriptionEvent.Error -> {
                        Log.w(AUDIO_TAG, "event ERROR ${event.message}")
                        _effects.trySend(ChatEffect.ShowError(event.message))
                    }

                    is TranscriptionEvent.State -> {
                        Log.d(AUDIO_TAG, "event STATE ${event.status}")
                        _state.update { current ->
                            current.copy(transcriptionStatus = event.status)
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                Log.d(AUDIO_TAG, "transcriptionLoop: cancelled")
                throw throwable
            }
            Log.e(AUDIO_TAG, "transcriptionLoop: failed", throwable)
            _state.update { current ->
                current.copy(transcriptionStatus = TranscriptionStatus.Error)
            }
            _effects.trySend(
                ChatEffect.ShowError(throwable.message ?: "Transcription failed"),
            )
        } finally {
            if (transcriptionJob?.isCancelled != false) {
                _state.update { current ->
                    current.copy(
                        livePartial = "",
                        transcriptionStatus = TranscriptionStatus.Idle,
                    )
                }
            }
        }
    }

    private suspend fun ensureWhisperReady() {
        withContext(Dispatchers.IO) {
            if (WhisperPaths.resolveExisting(appContext) == null) {
                whisperDownloader.download().collect { step ->
                    when (step) {
                        is WhisperDownloadState.InProgress -> {
                            _state.update { current ->
                                current.copy(
                                    transcriptionStatus = TranscriptionStatus.DownloadingModel(
                                        bytesDownloaded = step.bytesDownloaded,
                                        totalBytes = step.totalBytes,
                                    ),
                                )
                            }
                        }

                        WhisperDownloadState.Success -> Unit

                        is WhisperDownloadState.Failed -> {
                            throw IllegalStateException(step.message)
                        }
                    }
                }
            }
            whisperEngine.initialize()
        }
    }

    private fun resetConversation() {
        scanCursor = -1L
        cooldowns.clear()
        _state.update { current ->
            current.copy(
                captureThumbnails = emptyMap(),
                transcript = emptyList(),
                livePartial = "",
                lastCapture = null,
            )
        }
    }

    private fun dismissError() {
        when (_state.value.status) {
            is Status.Error -> {
                _state.update { current ->
                    current.copy(
                        status = if (engine.isInitialized) {
                            Status.Ready(engine.isGpu)
                        } else {
                            Status.Loading
                        },
                    )
                }
            }

            is Status.OntologyFailed -> {
                _state.update { current -> current.copy(status = Status.Loading) }
                loadModel()
            }

            is Status.DownloadFailed -> {
                _state.update { current ->
                    current.copy(
                        status = if (tokenStore.get() == null) {
                            Status.NeedsToken
                        } else {
                            Status.ModelMissing
                        },
                    )
                }
            }

            else -> Unit
        }
    }

    override fun onCleared() {
        super.onCleared()
        classifierJob?.cancel()
        transcriptionJob?.cancel()
        streamStateJob?.cancel()
        downloader.cancel()
        whisperDownloader.cancel()
        transcriptionService.close()
        runBlocking {
            whisperEngine.close()
        }
        engine.close()
    }

    private fun capturePhoto() {
        val stream = frameSource ?: run {
            _effects.trySend(ChatEffect.ShowError("Camera stream unavailable."))
            return
        }

        val caption = _state.value.caption
        viewModelScope.launch {
            val capture = runCatching { stream.captureClassifiedFrame(caption) }
                .getOrElse { throwable ->
                    _effects.trySend(ChatEffect.ShowError(throwable.message ?: "Capture failed"))
                    null
                }

            if (capture == null) {
                _effects.trySend(ChatEffect.ShowError("No live frame to capture."))
                return@launch
            }

            _state.update { current -> current.copy(lastCapture = capture) }
            _effects.trySend(
                ChatEffect.PhotoCaptured(
                    jpegUri = capture.jpegUri,
                    sidecarUri = capture.sidecarUri,
                ),
            )
        }
    }

    private suspend fun triggerScanLoop(stream: StreamViewModel) {
        try {
            while (true) {
                val delta =
                    _state.value.transcript
                        .filter { utterance -> utterance.id > scanCursor }
                if (delta.isEmpty()) {
                    delay(SCAN_INTERVAL_MS)
                    continue
                }

                val scanResult =
                    runCatching { runTriggerScan(delta) }
                        .getOrElse { throwable ->
                            if (throwable is CancellationException) {
                                throw throwable
                            }

                            Log.e(TAG, "Trigger scan failed", throwable)
                            _effects.trySend(
                                ChatEffect.ShowError(throwable.message ?: "Trigger scan failed"),
                            )
                            delay(ERROR_BACKOFF_MS)
                            null
                        }

                scanResult?.let(::applyTriggerHighlights)
                val firedTrigger = scanResult?.firedTrigger
                if (firedTrigger != null) {
                    val context = buildTriggerContext(firedTrigger, delta)
                    val jpeg = stream.latestFrameJpeg()
                    if (jpeg == null) {
                        Log.w(TAG, "trigger fire skipped: no live frame available")
                    } else {
                        Log.i(
                            TAG,
                            "trigger fire category=${firedTrigger.category} phrase=${firedTrigger.phrase}",
                        )
                        if (classifyAndSave(stream, jpeg, context)) {
                            cooldowns[firedTrigger.category] = System.currentTimeMillis()
                        }
                    }
                }

                scanCursor = delta.last().id
                yield()
            }
        } finally {
            _state.update { current ->
                if (current.isClassificationEnabled) {
                    current.copy(
                        isClassificationEnabled = false,
                        status = if (engine.isInitialized) {
                            Status.Ready(engine.isGpu)
                        } else {
                            current.status
                        },
                        caption = null,
                    )
                } else {
                    current
                }
            }
        }
    }

    private suspend fun runTriggerScan(delta: List<TranscriptUtterance>): TriggerScanResult {
        val deltaText = buildScanDelta(delta)
        if (deltaText.isBlank()) {
            return TriggerScanResult()
        }

        val toolMatches = mutableListOf<TriggerToolMatch>()
        var finalText = ""
        engine.reset()
        engine.generateWithTools(
            prompt = buildScanPrompt(deltaText),
            imageBytes = null,
            tools = listOf(triggerExecutor),
            maxRounds = 2,
        ).collect { event ->
            when (event) {
                is TurnEvent.ToolResult -> {
                    toolMatches += decodeTriggerMatches(event.json)
                }

                is TurnEvent.Final -> {
                    finalText = event.text
                }

                is TurnEvent.AssistantDelta,
                is TurnEvent.ToolCall,
                -> Unit
            }
        }

        val matches =
            if (toolMatches.isNotEmpty()) {
                toolMatches
            } else {
                val localMatches = buildLocalTriggerMatches(deltaText)
                if (localMatches.isNotEmpty()) {
                    Log.d(TAG, "trigger scan: using local fallback matches (${localMatches.size})")
                } else {
                    Log.d(TAG, "trigger scan: no tool matches")
                }
                localMatches
            }
        if (matches.isEmpty()) {
            return TriggerScanResult()
        }

        val positives = matches.filterNot { it.negated }
        val highlights = buildHighlightMap(delta, positives)
        if (positives.isEmpty()) {
            Log.d(TAG, "trigger scan: only negated matches present")
            return TriggerScanResult(highlightMap = highlights)
        }

        val orderedCandidates = selectGemmaCandidates(finalText, positives)
        if (orderedCandidates.isEmpty()) {
            Log.d(TAG, "trigger scan: Gemma chose no trigger fire")
            return TriggerScanResult(highlightMap = highlights)
        }

        val now = System.currentTimeMillis()
        for (candidate in orderedCandidates) {
            val lastFiredAt = cooldowns[candidate.category]
            if (lastFiredAt != null && now - lastFiredAt < candidate.cooldownMs) {
                Log.d(
                    TAG,
                    "cooldown hit for ${candidate.category}: ${now - lastFiredAt}ms < ${candidate.cooldownMs}ms",
                )
                continue
            }
            if (candidate.requiresCorroboration && !hasCorroboration(candidate, positives)) {
                Log.d(TAG, "trigger corroboration missing for ${candidate.id}")
                continue
            }
            return TriggerScanResult(
                firedTrigger = candidate,
                highlightMap = highlights,
            )
        }
        return TriggerScanResult(highlightMap = highlights)
    }

    private suspend fun classifyAndSave(
        stream: StreamViewModel,
        jpeg: ByteArray,
        context: TriggerContext,
    ): Boolean {
        _state.update { current -> current.copy(caption = null) }
        return runCatching {
            engine.reset()
            var statement = ""
            val designations = LinkedHashMap<String, Designation>()
            var caption: ClassifiedCaption? = null

            engine.generateWithTools(
                prompt = buildClassificationPrompt(context),
                imageBytes = jpeg,
                tools = listOf(snomedExecutor, icdExecutor, aisExecutor),
            ).collect { event ->
                when (event) {
                    is TurnEvent.AssistantDelta -> {
                        statement = parseStatement(event.text)
                        caption = buildCaption(statement, designations.values.toList())
                        _state.update { current ->
                            current.copy(caption = caption)
                        }
                    }

                    is TurnEvent.ToolCall -> Unit

                    is TurnEvent.ToolResult -> {
                        event.designation?.let { designation ->
                            designations[designation.source] = designation
                            caption = buildCaption(statement, designations.values.toList())
                            _state.update { current ->
                                current.copy(caption = caption)
                            }
                        }
                    }

                    is TurnEvent.Final -> {
                        event.designations.forEach { designation ->
                            designations[designation.source] = designation
                        }
                        caption = buildCaption(statement, designations.values.toList())
                        _state.update { current ->
                            current.copy(caption = caption)
                        }
                    }
                }
            }

            val capture = stream.captureClassifiedFrame(caption, context)
            if (capture == null) {
                _effects.trySend(ChatEffect.ShowError("No live frame to capture."))
                return@runCatching false
            }

            _state.update { current ->
                val updatedThumbnails =
                    capture.thumbnailBytes?.let { bytes ->
                        current.captureThumbnails +
                            (context.sourceUtteranceId to
                                TranscriptCaptureThumbnail(
                                    jpegUri = capture.jpegUri,
                                    bytes = bytes,
                                    capturedAt = capture.capturedAt,
                                    matchedPhrase = context.matchedPhrase,
                                    pointOfInterest = context.pointOfInterest,
                                ))
                    } ?: current.captureThumbnails
                current.copy(
                    lastCapture = capture,
                    captureThumbnails = updatedThumbnails,
                )
            }
            _effects.trySend(
                ChatEffect.PhotoCaptured(
                    jpegUri = capture.jpegUri,
                    sidecarUri = capture.sidecarUri,
                ),
            )
            true
        }.onFailure { throwable ->
            if (throwable is CancellationException) {
                throw throwable
            }

            Log.e(TAG, "Triggered classification failed", throwable)
            _effects.trySend(
                ChatEffect.ShowError(throwable.message ?: "Classification failed"),
            )
            _state.update { current -> current.copy(caption = null) }
            delay(ERROR_BACKOFF_MS)
        }.getOrDefault(false)
    }

    private fun buildScanDelta(delta: List<TranscriptUtterance>): String =
        delta.joinToString(separator = "\n") { utterance -> utterance.text }
            .takeLast(MAX_SCAN_CHARS)

    private fun buildScanPrompt(deltaText: String): String = buildString {
        appendLine("You are scanning EMS transcript text for image capture triggers.")
        appendLine("You MUST call trigger_lookup with the full transcript delta as the query before answering.")
        appendLine("Treat negated matches as suppressed unless there is a separate positive match.")
        appendLine("Any match with requiresCorroboration=true needs another positive trigger in the same delta.")
        appendLine("After the tool result, respond with exactly one line in this shape:")
        appendLine("triggers: <id> <category>; ...")
        appendLine("If nothing should fire, respond exactly:")
        appendLine("triggers: none")
        appendLine("<transcript_delta>")
        appendLine(deltaText)
        appendLine("</transcript_delta>")
    }

    private fun buildClassificationPrompt(context: TriggerContext): String = buildString {
        appendLine("Trigger category: ${context.category}")
        appendLine("Matched phrase: ${context.matchedPhrase}")
        appendLine("Point of interest (what to look for in the image): ${context.pointOfInterest}")
        appendLine("Source utterance: ${context.sourceText}")
        appendLine("Focus the description on the point of interest above if it is visible; if not, say so.")
        append(CLASSIFICATION_PROMPT)
    }

    private fun decodeTriggerMatches(json: String): List<TriggerToolMatch> =
        runCatching {
            triggerJson.decodeFromString<TriggerToolPayload>(json).matches
        }.getOrDefault(emptyList())

    private fun buildLocalTriggerMatches(deltaText: String): List<TriggerToolMatch> =
        triggerIndex.match(deltaText).map { match ->
            TriggerToolMatch(
                id = match.entry.id,
                category = match.entry.category,
                phrase = match.matchedPhrase,
                pointOfInterest = match.pointOfInterest,
                requiresCorroboration = match.entry.requiresCorroboration,
                negated = match.negated,
                cooldownMs = match.entry.cooldownMs,
            )
        }

    private fun buildHighlightMap(
        delta: List<TranscriptUtterance>,
        positives: List<TriggerToolMatch>,
    ): Map<Long, List<String>> {
        if (positives.isEmpty()) {
            return emptyMap()
        }
        val byId = positives.associateBy { it.id }
        return delta.associate { utterance ->
            val phrases =
                triggerIndex.match(utterance.text)
                    .mapNotNull { match -> if (byId.containsKey(match.entry.id)) match.matchedPhrase else null }
                    .distinct()
            utterance.id to phrases
        }.filterValues { phrases -> phrases.isNotEmpty() }
    }

    private fun applyTriggerHighlights(result: TriggerScanResult) {
        if (result.highlightMap.isEmpty()) {
            return
        }
        _state.update { current ->
            current.copy(
                transcript = current.transcript.map { utterance ->
                    val newPhrases = result.highlightMap[utterance.id] ?: return@map utterance
                    val mergedPhrases = (utterance.highlightedPhrases + newPhrases).distinct()
                    if (mergedPhrases == utterance.highlightedPhrases) {
                        utterance
                    } else {
                        utterance.copy(highlightedPhrases = mergedPhrases)
                    }
                },
            )
        }
    }

    private fun selectGemmaCandidates(
        finalText: String,
        positives: List<TriggerToolMatch>,
    ): List<TriggerToolMatch> {
        val line =
            finalText
                .lineSequence()
                .map(String::trim)
                .lastOrNull { text -> text.startsWith(SCAN_RESPONSE_PREFIX, ignoreCase = true) }
                ?: return positives
        val payload = line.substringAfter(':', "").trim()
        if (payload.equals("none", ignoreCase = true)) {
            return emptyList()
        }

        val byId = positives.associateBy { it.id }
        val ordered =
            payload
                .split(';')
                .mapNotNull { chunk ->
                    val id = chunk.trim().substringBefore(' ').trim()
                    byId[id]
                }
        return if (ordered.isEmpty()) positives else ordered
    }

    private fun hasCorroboration(
        candidate: TriggerToolMatch,
        positives: List<TriggerToolMatch>,
    ): Boolean = positives.any { other ->
        other !== candidate &&
            !other.negated
    }

    private fun buildTriggerContext(
        candidate: TriggerToolMatch,
        delta: List<TranscriptUtterance>,
    ): TriggerContext {
        val sourceUtterance =
            delta
                .asReversed()
                .firstOrNull { utterance ->
                    triggerIndex.match(utterance.text)
                        .any { match -> match.entry.id == candidate.id || match.matchedPhrase == candidate.phrase }
                }
                ?: delta.last()

        return TriggerContext(
            category = candidate.category.name,
            matchedPhrase = candidate.phrase,
            pointOfInterest = candidate.pointOfInterest,
            sourceUtteranceId = sourceUtterance.id,
            sourceText = sourceUtterance.text,
        )
    }

    private fun buildCaption(
        statement: String,
        designations: List<Designation>,
    ): ClassifiedCaption? {
        val cleanedStatement = statement.trim()
        val orderedDesignations = designations.sortedBy(::designationSortKey)
        return if (cleanedStatement.isBlank() && orderedDesignations.isEmpty()) {
            null
        } else {
            ClassifiedCaption(
                statement = cleanedStatement,
                designations = orderedDesignations,
            )
        }
    }

    private fun parseStatement(raw: String): String {
        val beforeToolCall = raw.substringBefore("<tool_call>")
        return beforeToolCall
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotBlank() && !line.startsWith("designations:", ignoreCase = true) }
            .joinToString(separator = " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun designationSortKey(designation: Designation): Int = when (designation.source) {
        "snomed" -> 0
        "icd10cm" -> 1
        "ais1985" -> 2
        else -> Int.MAX_VALUE
    }

    @Serializable
    private data class TriggerToolPayload(
        val source: String,
        val matches: List<TriggerToolMatch> = emptyList(),
    )

    @Serializable
    private data class TriggerToolMatch(
        val id: String,
        val category: TriggerCategory,
        val phrase: String,
        val pointOfInterest: String,
        val requiresCorroboration: Boolean,
        val negated: Boolean,
        val cooldownMs: Long,
    )

    private data class TriggerScanResult(
        val firedTrigger: TriggerToolMatch? = null,
        val highlightMap: Map<Long, List<String>> = emptyMap(),
    )

    private companion object {
        const val CLASSIFICATION_PROMPT =
            "Classify this clinical scene in one short sentence, then call SNOMED, ICD, and AIS tools as needed."
        const val SCAN_INTERVAL_MS = 2_000L
        const val MAX_SCAN_CHARS = 1_500
        const val SCAN_RESPONSE_PREFIX = "triggers:"
        const val ERROR_BACKOFF_MS = 1_000L
        const val TAG = "ChatViewModel"
        const val AUDIO_TAG = "AudioPipe/ChatVM"
        val WHITESPACE_REGEX = Regex("\\s+")
        val triggerJson = Json {
            ignoreUnknownKeys = true
        }
    }
}
