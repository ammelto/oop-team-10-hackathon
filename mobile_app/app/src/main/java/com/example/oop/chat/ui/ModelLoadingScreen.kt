package com.example.oop.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.oop.R
import com.example.oop.chat.ChatViewModel
import com.example.oop.chat.mvi.ChatIntent
import com.example.oop.chat.mvi.Status

@Composable
fun ModelLoadingScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status = state.status
    val showTokenDialog = status is Status.NeedsToken
    var tokenValue by rememberSaveable(showTokenDialog) { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.model_loading_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.model_loading_subtitle),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusIndicator(status = status)

            Text(
                text = status.toLoadingText(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            StatusAction(
                status = status,
                onRetry = {
                    if (status is Status.DownloadFailed) {
                        viewModel.onIntent(ChatIntent.StartDownload)
                    } else {
                        viewModel.onIntent(ChatIntent.LoadModel)
                    }
                },
                onEnterToken = { viewModel.onIntent(ChatIntent.StartDownload) },
                onDismiss = { viewModel.onIntent(ChatIntent.DismissError) },
            )
        }
    }

    if (showTokenDialog) {
        TokenDialog(
            token = tokenValue,
            onTokenChanged = { tokenValue = it },
            onSubmit = {
                viewModel.onIntent(ChatIntent.SubmitToken(tokenValue))
                tokenValue = ""
            },
        )
    }
}

@Composable
private fun StatusIndicator(status: Status) {
    when (status) {
        is Status.Downloading -> {
            if (status.totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (status.bytesDownloaded.toFloat() / status.totalBytes.toFloat())
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .width(240.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .width(240.dp),
                )
            }
        }

        is Status.OntologyInstalling -> {
            if (status.total > 0L) {
                LinearProgressIndicator(
                    progress = {
                        (status.bytes.toFloat() / status.total.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .width(240.dp),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .width(240.dp),
                )
            }
        }

        is Status.OntologyFailed,
        is Status.DownloadFailed,
        is Status.Error,
        Status.ModelMissing,
        Status.NeedsToken,
        -> Unit

        else -> CircularProgressIndicator()
    }
}

@Composable
private fun StatusAction(
    status: Status,
    onRetry: () -> Unit,
    onEnterToken: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (status) {
        Status.ModelMissing -> {
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(text = stringResource(R.string.action_retry))
            }
        }

        Status.NeedsToken -> {
            Button(
                onClick = onEnterToken,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(text = stringResource(R.string.action_enter_token))
            }
        }

        is Status.DownloadFailed -> {
            Button(
                onClick = if (status.canRetry) onRetry else onDismiss,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(
                    text = stringResource(
                        if (status.canRetry) R.string.action_retry else R.string.action_dismiss,
                    ),
                )
            }
        }

        is Status.OntologyFailed -> {
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(text = stringResource(R.string.action_retry))
            }
        }

        is Status.Error -> {
            Button(
                onClick = onDismiss,
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text(text = stringResource(R.string.action_dismiss))
            }
        }

        else -> Unit
    }
}

@Composable
private fun Status.toLoadingText(): String {
    val context = LocalContext.current
    return when (this) {
        Status.Loading -> context.getString(R.string.status_loading_model)
        is Status.OntologyInstalling -> context.getString(R.string.status_ontology_installing, file)
        is Status.OntologyFailed -> context.getString(R.string.status_ontology_failed, message)
        Status.ModelMissing -> context.getString(R.string.status_model_missing)
        Status.NeedsToken -> context.getString(R.string.status_needs_token)
        is Status.Downloading -> {
            if (totalBytes > 0L) {
                val percent = ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                context.getString(R.string.status_downloading_progress, percent)
            } else {
                context.getString(R.string.status_downloading)
            }
        }
        is Status.DownloadFailed -> context.getString(R.string.status_download_failed, message)
        is Status.Error -> context.getString(R.string.status_error, message)
        Status.Generating -> context.getString(R.string.status_generating)
        Status.Classifying -> context.getString(R.string.status_classifying)
        is Status.Ready -> {
            context.getString(
                if (isGpu) R.string.status_ready_gpu else R.string.status_ready_cpu,
            )
        }
    }
}
