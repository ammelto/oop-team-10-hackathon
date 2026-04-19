package com.example.oop.chat.ui

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.oop.R
import com.example.oop.chat.ChatViewModel
import com.example.oop.chat.mvi.ChatEffect
import com.example.oop.chat.mvi.ChatIntent
import com.example.oop.chat.mvi.ChatUiState
import com.example.oop.chat.mvi.ClassifiedCaption
import com.example.oop.chat.mvi.Status
import com.example.oop.stream.StreamViewModel
import com.example.oop.stream.ui.CameraPane
import kotlinx.coroutines.flow.Flow

private enum class ChatTab(@StringRes val labelRes: Int) {
    Transcript(R.string.tab_transcript),
    CameraDebug(R.string.tab_camera_debug),
}

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    streamViewModel: StreamViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChatScreen(
        state = state,
        onIntent = viewModel::onIntent,
        effects = viewModel.effects,
        streamViewModel = streamViewModel,
        modifier = modifier,
    )
}

@Composable
fun ChatScreen(
    state: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    effects: Flow<ChatEffect>,
    streamViewModel: StreamViewModel,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val isDownloading = state.status is Status.Downloading
    val showTokenDialog = state.status is Status.NeedsToken
    val captureSuccessMessage = stringResource(R.string.capture_success)
    val context = LocalContext.current
    var tokenValue by rememberSaveable(showTokenDialog) { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(ChatTab.Transcript) }

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is ChatEffect.ScrollToBottom -> Unit

                is ChatEffect.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = effect.message,
                        duration = SnackbarDuration.Short,
                    )
                }

                is ChatEffect.PhotoCaptured -> {
                    snackbarHostState.showSnackbar(
                        message = captureSuccessMessage,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
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
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                ChatTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(text = stringResource(tab.labelRes)) },
                    )
                }
            }

            if (isDownloading) {
                val progress = state.status as Status.Downloading
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

            when (selectedTab) {
                ChatTab.Transcript -> {
                    TranscriptPane(
                        utterances = state.transcript,
                        livePartial = state.livePartial,
                        status = state.transcriptionStatus,
                        isClassifying = state.isClassificationEnabled,
                        onToggleClassification = { enabled -> onIntent(ChatIntent.ToggleClassification(enabled)) },
                        onClear = { onIntent(ChatIntent.ResetConversation) },
                        thumbnails = state.captureThumbnails,
                        onThumbnailClick = { thumbnail ->
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(thumbnail.jpegUri, "image/jpeg")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                    )
                }

                ChatTab.CameraDebug -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                    ) {
                        CameraPane(
                            streamViewModel = streamViewModel,
                            isCaptureEnabled = state.isClassificationEnabled && state.caption != null,
                            onCapture = { onIntent(ChatIntent.CapturePhoto) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        CaptionBar(
                            enabled = state.isClassificationEnabled,
                            caption = state.caption,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
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

@Composable
private fun CaptionBar(
    enabled: Boolean,
    caption: ClassifiedCaption?,
    modifier: Modifier = Modifier,
) {
    if (!enabled) {
        return
    }

    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = caption?.statement?.takeIf { it.isNotBlank() } ?: stringResource(R.string.caption_placeholder),
                style = MaterialTheme.typography.bodyMedium,
            )
            val designations = caption?.designations.orEmpty()
            if (designations.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    designations.forEach { designation ->
                        DesignationChip(designation = designation)
                    }
                }
            }
        }
    }
}
