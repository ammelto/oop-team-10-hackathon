package com.example.oop.stream

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.oop.chat.mvi.ClassifiedCaption
import com.example.oop.wearables.WearablesViewModel
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {
    private companion object {
        private const val TAG = "DAT:STREAM:ViewModel"
        private val INITIAL_STATE = StreamUiState()
        private val SESSION_TERMINAL_STATES = setOf(StreamSessionState.CLOSED)
        private const val FRAME_LOG_INTERVAL = 30
        private const val THUMBNAIL_MAX_EDGE_PX = 160
        private const val THUMBNAIL_JPEG_QUALITY = 70
    }

    private var frameCounter: Int = 0

    private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
    private val _uiState = MutableStateFlow(INITIAL_STATE)

    val uiState = _uiState.asStateFlow()

    private var session: Session? = null
    private var stream: Stream? = null
    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private var errorJob: Job? = null
    private var sessionStateJob: Job? = null
    private var presentationQueue: PresentationQueue? = null

    fun startStream() {
        Log.d(
            TAG,
            "startStream() invoked; sessionStateJobActive=${sessionStateJob?.isActive == true}, " +
                "stream=${stream != null}, session=${session != null}",
        )
        if (sessionStateJob?.isActive == true || stream != null) {
            Log.w(
                TAG,
                "startStream() early-return: session loop already active or stream already set. " +
                    "Current streamSessionState=${_uiState.value.streamSessionState}",
            )
            return
        }

        _uiState.update { current -> current.copy(streamSessionState = StreamSessionState.STARTING) }
        Log.d(TAG, "startStream(): set streamSessionState=STARTING")

        videoJob?.cancel()
        stateJob?.cancel()
        errorJob?.cancel()
        sessionStateJob?.cancel()
        presentationQueue?.stop()
        presentationQueue = null
        frameCounter = 0

        val queue =
            PresentationQueue(
                bufferDelayMs = 100L,
                maxQueueSize = 15,
                onFrameReady = { frame ->
                    _uiState.update { current ->
                        val newCount = current.videoFrameCount + 1
                        if (newCount == 1) {
                            Log.i(TAG, "First video frame presented to UI (bitmap=${frame.bitmap.width}x${frame.bitmap.height})")
                        }
                        current.copy(
                            videoFrame = frame.bitmap,
                            videoFrameCount = newCount,
                        )
                    }
                },
            )
        presentationQueue = queue
        queue.start()
        Log.d(TAG, "startStream(): PresentationQueue started")

        if (session == null) {
            Log.d(TAG, "startStream(): creating Wearables session via $deviceSelector")
            Wearables.createSession(deviceSelector)
                .onSuccess { createdSession ->
                    Log.i(TAG, "startStream(): createSession success; calling session.start()")
                    session = createdSession
                    session?.start()
                }
                .onFailure { error, _ ->
                    wearablesViewModel.setRecentError("Failed to create camera session: ${error.description}")
                    Log.e(TAG, "startStream(): Failed to create session: ${error.description}")
                }
            if (session == null) {
                Log.e(TAG, "startStream(): session is null after createSession; aborting")
                return
            }
        } else {
            Log.d(TAG, "startStream(): reusing existing session")
        }

        startStreamInternal()
    }

    fun stopStream() {
        Log.d(
            TAG,
            "stopStream() invoked; stream=${stream != null}, session=${session != null}, " +
                "streamSessionState=${_uiState.value.streamSessionState}",
        )
        videoJob?.cancel()
        videoJob = null
        stateJob?.cancel()
        stateJob = null
        errorJob?.cancel()
        errorJob = null
        sessionStateJob?.cancel()
        sessionStateJob = null
        presentationQueue?.stop()
        presentationQueue = null
        _uiState.update { INITIAL_STATE }
        stream?.stop()
        stream = null
        session?.stop()
        session = null
        frameCounter = 0
        Log.d(TAG, "stopStream(): cleanup complete")
    }

    suspend fun snapshotToUri(): Uri? = withContext(Dispatchers.IO) {
        val sourceBitmap = uiState.value.videoFrame ?: return@withContext null
        val copiedBitmap = sourceBitmap.copy(sourceBitmap.config ?: Bitmap.Config.ARGB_8888, false)
        val context = getApplication<Application>()
        val directory = File(context.cacheDir, "glasses_snapshots").apply { mkdirs() }
        val file = File(directory, "frame_${System.currentTimeMillis()}.jpg")

        try {
            FileOutputStream(file).use { output ->
                check(copiedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                    "Unable to compress stream snapshot"
                }
            }
        } finally {
            if (copiedBitmap !== sourceBitmap && !copiedBitmap.isRecycled) {
                copiedBitmap.recycle()
            }
        }

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    suspend fun latestFrameJpeg(
        maxEdgePx: Int = 512,
        quality: Int = 80,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val sourceBitmap = uiState.value.videoFrame ?: return@withContext null
        val copiedBitmap = sourceBitmap.copy(sourceBitmap.config ?: Bitmap.Config.ARGB_8888, false)
        val scaledBitmap = copiedBitmap.scaleToMaxEdge(maxEdgePx)

        try {
            ByteArrayOutputStream().use { output ->
                check(scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    "Unable to compress classification frame"
                }
                output.toByteArray()
            }
        } finally {
            if (scaledBitmap !== copiedBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (!copiedBitmap.isRecycled) {
                copiedBitmap.recycle()
            }
        }
    }

    suspend fun captureClassifiedFrame(
        caption: ClassifiedCaption?,
        trigger: TriggerContext? = null,
    ): CaptureSummary? = withContext(Dispatchers.IO) {
        val sourceBitmap = uiState.value.videoFrame ?: return@withContext null
        val copiedBitmap = sourceBitmap.copy(sourceBitmap.config ?: Bitmap.Config.ARGB_8888, false)
        val context = getApplication<Application>()
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "photos").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()
        val jpegFile = File(directory, "classified_$timestamp.jpg")
        val sidecarFile = File(directory, "classified_$timestamp.json")

        val thumbnailBytes =
            try {
                FileOutputStream(jpegFile).use { output ->
                    check(copiedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                        "Unable to compress captured frame"
                    }
                }
                buildThumbnailBytes(
                    sourceBitmap = copiedBitmap,
                    maxEdgePx = THUMBNAIL_MAX_EDGE_PX,
                    quality = THUMBNAIL_JPEG_QUALITY,
                )
            } finally {
                if (copiedBitmap !== sourceBitmap && !copiedBitmap.isRecycled) {
                    copiedBitmap.recycle()
                }
            }

        val sidecar = CaptureSidecar(
            schemaVersion = 3,
            capturedAt = timestamp,
            statement = caption?.statement,
            designations = caption?.designations.orEmpty(),
            trigger = trigger,
        )
        sidecarFile.writeText(CaptureJson.encodeToString(sidecar))

        CaptureSummary(
            jpegUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                jpegFile,
            ),
            sidecarUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                sidecarFile,
            ),
            capturedAt = timestamp,
            caption = caption,
            trigger = trigger,
            thumbnailBytes = thumbnailBytes,
        )
    }

    private fun buildThumbnailBytes(
        sourceBitmap: Bitmap,
        maxEdgePx: Int,
        quality: Int,
    ): ByteArray {
        val largestEdge = max(sourceBitmap.width, sourceBitmap.height)
        val scaledBitmap =
            if (largestEdge <= maxEdgePx) {
                sourceBitmap
            } else {
                val scale = maxEdgePx.toFloat() / largestEdge.toFloat()
                val scaledWidth = (sourceBitmap.width * scale).toInt().coerceAtLeast(1)
                val scaledHeight = (sourceBitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(sourceBitmap, scaledWidth, scaledHeight, true)
            }

        return try {
            ByteArrayOutputStream().use { output ->
                check(scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    "Unable to compress thumbnail frame"
                }
                output.toByteArray()
            }
        } finally {
            if (scaledBitmap !== sourceBitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }
    }

    private fun startStreamInternal() {
        Log.d(TAG, "startStreamInternal(): subscribing to session.state")
        sessionStateJob =
            viewModelScope.launch {
                session?.state?.collect { currentState ->
                    Log.d(TAG, "session.state emission: $currentState")
                    if (currentState == DeviceSessionState.STARTED) {
                        Log.i(TAG, "Session STARTED; requesting addStream(MEDIUM @24fps)")
                        videoJob?.cancel()
                        stateJob?.cancel()
                        errorJob?.cancel()
                        stream?.stop()
                        stream = null

                        session
                            ?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24))
                            ?.onSuccess { addedStream ->
                                Log.i(TAG, "addStream success; wiring collectors and calling stream.start()")
                                stream = addedStream
                                videoJob =
                                    viewModelScope.launch {
                                        Log.d(TAG, "videoStream collector launched")
                                        stream?.videoStream?.collect(::handleVideoFrame)
                                        Log.w(TAG, "videoStream collector ended")
                                    }
                                stateJob =
                                    viewModelScope.launch {
                                        stream?.state?.collect { streamState ->
                                            val previousState = _uiState.value.streamSessionState
                                            Log.d(
                                                TAG,
                                                "stream.state emission: $previousState -> $streamState",
                                            )
                                            _uiState.update { current ->
                                                current.copy(streamSessionState = streamState)
                                            }

                                            val wasActive = previousState !in SESSION_TERMINAL_STATES
                                            val isTerminated = streamState in SESSION_TERMINAL_STATES
                                            if (wasActive && isTerminated) {
                                                Log.w(
                                                    TAG,
                                                    "stream terminated ($previousState -> $streamState); calling stopStream()",
                                                )
                                                wearablesViewModel.disableChat()
                                                stopStream()
                                            }
                                        }
                                    }
                                errorJob =
                                    viewModelScope.launch {
                                        stream?.errorStream?.collect { error ->
                                            Log.e(TAG, "stream.errorStream emission: $error (${error.description})")
                                            if (error == StreamError.HINGE_CLOSED) {
                                                wearablesViewModel.disableChat("Glasses were closed. Stream stopped.")
                                                stopStream()
                                            } else {
                                                wearablesViewModel.setRecentError(
                                                    "Stream error: ${error.description}",
                                                )
                                            }
                                        }
                                    }
                                stream?.start()
                                Log.d(TAG, "stream.start() called")
                            }
                            ?.onFailure { error, _ ->
                                wearablesViewModel.setRecentError(
                                    "Failed to add camera stream: ${error.description}",
                                )
                                Log.e(TAG, "Failed to add stream: ${error.description}")
                            }
                    }
                }
                Log.w(TAG, "session.state collector ended")
            }
    }

    private fun handleVideoFrame(videoFrame: VideoFrame) {
        frameCounter += 1
        if (frameCounter == 1) {
            Log.i(
                TAG,
                "First raw video frame received from stream: ${videoFrame.width}x${videoFrame.height}, " +
                    "ptsUs=${videoFrame.presentationTimeUs}, remaining=${videoFrame.buffer.remaining()}",
            )
        } else if (frameCounter % FRAME_LOG_INTERVAL == 0) {
            Log.d(
                TAG,
                "Raw frames received so far: $frameCounter (latest ${videoFrame.width}x${videoFrame.height}, " +
                    "ptsUs=${videoFrame.presentationTimeUs})",
            )
        }

        val bitmap =
            YuvToBitmapConverter.convert(
                videoFrame.buffer,
                videoFrame.width,
                videoFrame.height,
            )
        if (bitmap != null) {
            presentationQueue?.enqueue(bitmap, videoFrame.presentationTimeUs)
        } else {
            Log.e(
                TAG,
                "Failed to convert YUV frame (frame #$frameCounter, ${videoFrame.width}x${videoFrame.height}, " +
                    "remaining=${videoFrame.buffer.remaining()})",
            )
        }
    }

    private fun Bitmap.scaleToMaxEdge(maxEdgePx: Int): Bitmap {
        val largestEdge = maxOf(width, height)
        if (largestEdge <= maxEdgePx) {
            return this
        }

        val scale = maxEdgePx.toFloat() / largestEdge.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
    }

    class Factory(
        private val application: Application,
        private val wearablesViewModel: WearablesViewModel,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return StreamViewModel(
                    application = application,
                    wearablesViewModel = wearablesViewModel,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

@Serializable
private data class CaptureSidecar(
    val schemaVersion: Int,
    val capturedAt: Long,
    val statement: String?,
    val designations: List<com.example.oop.ontology.Designation>,
    val trigger: TriggerContext? = null,
)

private val CaptureJson = Json {
    encodeDefaults = true
    explicitNulls = true
}
