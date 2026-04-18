package com.example.oop.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.oop.chat.model.ChatMessage
import com.example.oop.chat.model.Role
import com.example.oop.chat.mvi.ChatEffect
import com.example.oop.chat.mvi.ChatIntent
import com.example.oop.chat.mvi.ChatUiState
import com.example.oop.chat.mvi.Status
import com.example.oop.llm.DownloadState
import com.example.oop.llm.LiteRtLmEngine
import com.example.oop.llm.ModelDownloader
import com.example.oop.llm.ModelPaths
import com.example.oop.llm.ModelNotFoundException
import com.example.oop.llm.TokenStore
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val engine = LiteRtLmEngine(appContext)
    private val tokenStore = TokenStore(appContext)
    private val downloader = ModelDownloader(appContext, tokenStore)
    private val _state = MutableStateFlow(ChatUiState())
    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)

    private var nextId = 0L
    private var isLoadingModel = false
    private var currentDownloadJob: Job? = null

    val state = _state.asStateFlow()
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            ChatIntent.LoadModel -> loadModel()
            ChatIntent.StartDownload -> startDownload()
            is ChatIntent.SubmitToken -> submitToken(intent.value)
            ChatIntent.CancelDownload -> cancelDownload()
            is ChatIntent.InputChanged -> inputChanged(intent.text)
            is ChatIntent.AttachPhoto -> attachPhoto(intent.uri)
            ChatIntent.ClearPendingPhoto -> clearPendingPhoto()
            ChatIntent.SendMessage -> sendMessage()
            ChatIntent.ResetConversation -> resetConversation()
            ChatIntent.DismissError -> dismissError()
        }
    }

    private fun loadModel() {
        if (engine.isInitialized || isLoadingModel) {
            return
        }

        viewModelScope.launch {
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

                        DownloadState.Success -> {
                            loadModel()
                        }

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
        downloader.cancel()
        downloader.clearPartial()
        currentDownloadJob?.cancel()
        currentDownloadJob = null
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

    private fun inputChanged(text: String) {
        _state.update { current -> current.copy(input = text) }
    }

    private fun attachPhoto(uri: Uri) {
        _state.update { current -> current.copy(pendingImage = uri) }
    }

    private fun clearPendingPhoto() {
        _state.update { current -> current.copy(pendingImage = null) }
    }

    private fun sendMessage() {
        val snapshot = _state.value
        if (!snapshot.canSend) {
            return
        }

        val prompt = snapshot.input.trim()
        val pendingImage = snapshot.pendingImage
        val userMessage = ChatMessage(
            id = nextId++,
            role = Role.USER,
            text = prompt,
            imageUri = pendingImage,
        )
        val assistantPlaceholder = ChatMessage(id = nextId++, role = Role.ASSISTANT, text = "")
        val updatedMessages = snapshot.messages + userMessage + assistantPlaceholder

        _state.update { current ->
            current.copy(
                messages = updatedMessages,
                input = "",
                pendingImage = null,
                status = Status.Generating,
            )
        }

        viewModelScope.launch {
            _effects.trySend(ChatEffect.ScrollToBottom(updatedMessages.lastIndex))

            val imageBytes = runCatching {
                pendingImage?.let { uri ->
                    withContext(Dispatchers.IO) {
                        decodeAttachment(uri)
                    }
                }
            }.getOrElse { throwable ->
                Log.e(TAG, "Failed to decode attachment", throwable)
                _state.update { current ->
                    current.copy(
                        messages = snapshot.messages,
                        input = prompt,
                        pendingImage = pendingImage,
                        status = if (engine.isInitialized) {
                            Status.Ready(engine.isGpu)
                        } else {
                            snapshot.status
                        },
                    )
                }
                _effects.trySend(
                    ChatEffect.ShowError(
                        "Could not read photo: ${throwable.message ?: throwable.javaClass.simpleName}",
                    ),
                )
                return@launch
            }

            runCatching {
                engine.generate(prompt, imageBytes).collect { running ->
                    _state.update { current ->
                        val mutableMessages = current.messages.toMutableList()
                        val lastIndex = mutableMessages.lastIndex
                        if (lastIndex >= 0) {
                            mutableMessages[lastIndex] = mutableMessages[lastIndex].copy(text = running)
                        }
                        current.copy(messages = mutableMessages)
                    }
                    _effects.trySend(ChatEffect.ScrollToBottom(_state.value.messages.lastIndex))
                }
            }.onFailure { throwable ->
                Log.e(TAG, "Generation failed", throwable)
                val errorMessage = throwable.message
                    ?: throwable.cause?.message
                    ?: throwable.javaClass.simpleName
                _state.update { current ->
                    current.copy(
                        messages = current.messages.dropLast(1),
                        status = Status.Error(errorMessage),
                    )
                }
                _effects.trySend(ChatEffect.ShowError(errorMessage))
                return@launch
            }

            _state.update { current ->
                current.copy(status = Status.Ready(engine.isGpu))
            }
            _effects.trySend(ChatEffect.ScrollToBottom(_state.value.messages.lastIndex))
        }
    }

    private fun resetConversation() {
        engine.reset()
        _state.update { current ->
            current.copy(
                messages = emptyList(),
                input = "",
                status = if (engine.isInitialized) {
                    Status.Ready(engine.isGpu)
                } else {
                    current.status
                },
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
        downloader.cancel()
        engine.close()
    }

    private fun decodeAttachment(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        val boundsStream = appContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open photo")
        boundsStream.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        val sampleSize = calculateInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxEdgePx = MAX_IMAGE_EDGE_PX,
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val decodeStream = appContext.contentResolver.openInputStream(uri)
            ?: error("Unable to open photo")
        val decoded = decodeStream.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: error("Unable to decode photo")

        val scaled = decoded.scaleToMaxEdge(MAX_IMAGE_EDGE_PX)
        return ByteArrayOutputStream().use { output ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Unable to compress photo"
            }
            if (scaled !== decoded) {
                scaled.recycle()
            }
            decoded.recycle()
            output.toByteArray()
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

    private fun calculateInSampleSize(width: Int, height: Int, maxEdgePx: Int): Int {
        if (width <= 0 || height <= 0) {
            return 1
        }
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth > maxEdgePx || sampledHeight > maxEdgePx) {
            sampledWidth /= 2
            sampledHeight /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    private companion object {
        const val MAX_IMAGE_EDGE_PX = 768
        const val JPEG_QUALITY = 85
        const val TAG = "ChatViewModel"
    }
}
