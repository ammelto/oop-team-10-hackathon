package com.example.oop.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.oop.chat.CameraPhoto
import com.example.oop.chat.ChatViewModel
import com.example.oop.chat.mvi.ChatEffect
import com.example.oop.chat.mvi.ChatIntent
import com.example.oop.chat.mvi.ChatUiState
import com.example.oop.chat.mvi.Status
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenWearables: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatScreen(
        state = state,
        onIntent = viewModel::onIntent,
        effects = viewModel.effects,
        onOpenWearables = onOpenWearables,
    )
}

@Composable
fun ChatScreen(
    state: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    effects: Flow<ChatEffect>,
    onOpenWearables: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val isDownloading = state.status is Status.Downloading
    val showTokenDialog = state.status is Status.NeedsToken
    var tokenValue by rememberSaveable(showTokenDialog) { mutableStateOf("") }
    var pendingCaptureUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCapturePath by rememberSaveable { mutableStateOf<String?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCaptureUri?.toUri()
        val file = pendingCapturePath?.let(::File)
        pendingCaptureUri = null
        pendingCapturePath = null
        when {
            !success -> Unit
            uri == null -> Unit
            file != null && (!file.exists() || file.length() == 0L) -> {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Photo didn't save. Please try again.",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
            else -> onIntent(ChatIntent.AttachPhoto(uri))
        }
    }
    val requestCameraPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val capture = CameraPhoto.newCapture(context)
            pendingCaptureUri = capture.uri.toString()
            pendingCapturePath = capture.file.absolutePath
            takePicture.launch(capture.uri)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = "Camera permission is required to take a photo.",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    LaunchedEffect(listState, effects) {
        effects.collect { effect ->
            when (effect) {
                is ChatEffect.ScrollToBottom -> {
                    if (effect.index >= 0) {
                        listState.scrollToItem(effect.index)
                    }
                }

                is ChatEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            StatusBar(
                status = state.status,
                onReset = { onIntent(ChatIntent.ResetConversation) },
                onRetry = {
                    if (state.status is Status.DownloadFailed) {
                        onIntent(ChatIntent.StartDownload)
                    } else {
                        onIntent(ChatIntent.LoadModel)
                    }
                },
                onEnterToken = { onIntent(ChatIntent.StartDownload) },
                onCancelDownload = { onIntent(ChatIntent.CancelDownload) },
                onDismissError = { onIntent(ChatIntent.DismissError) },
                onOpenWearables = onOpenWearables,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isDownloading) {
                    val progress = state.status
                    if (progress.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = {
                                (progress.bytesDownloaded.toFloat() / progress.totalBytes.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                InputBar(
                    input = state.input,
                    pendingImage = state.pendingImage,
                    canSend = state.canSend,
                    onInputChanged = { onIntent(ChatIntent.InputChanged(it)) },
                    onRequestCapture = {
                        val permission = Manifest.permission.CAMERA
                        if (
                            ContextCompat.checkSelfPermission(context, permission) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            val capture = CameraPhoto.newCapture(context)
                            pendingCaptureUri = capture.uri.toString()
                            pendingCapturePath = capture.file.absolutePath
                            takePicture.launch(capture.uri)
                        } else {
                            requestCameraPermission.launch(permission)
                        }
                    },
                    onClearPending = { onIntent(ChatIntent.ClearPendingPhoto) },
                    onSend = { onIntent(ChatIntent.SendMessage) },
                )
            }
        },
    ) { paddingValues ->
        MessageList(
            messages = state.messages,
            listState = listState,
            isGenerating = state.status is Status.Generating,
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        )
    }

    if (showTokenDialog) {
        TokenDialog(
            token = tokenValue,
            onTokenChanged = { tokenValue = it },
            onSubmit = {
                onIntent(ChatIntent.SubmitToken(tokenValue))
                tokenValue = ""
            },
        )
    }
}
