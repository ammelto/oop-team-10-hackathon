package com.example.oop.chat.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.oop.R
import com.example.oop.chat.mvi.Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusBar(
    status: Status,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onEnterToken: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenterAlignedTopAppBar(
        modifier = modifier.fillMaxWidth(),
        title = {
            Text(
                text = status.toStatusText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            when (status) {
                is Status.Ready -> {
                    IconButton(onClick = onReset) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_reset),
                        )
                    }
                }

                Status.ModelMissing -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_retry),
                        )
                    }
                }

                Status.NeedsToken -> {
                    IconButton(onClick = onEnterToken) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = stringResource(R.string.action_enter_token),
                        )
                    }
                }

                is Status.Downloading -> {
                    IconButton(onClick = onCancelDownload) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel_download),
                        )
                    }
                }

                is Status.DownloadFailed -> {
                    IconButton(
                        onClick = if (status.canRetry) onRetry else onDismissError,
                    ) {
                        Icon(
                            imageVector = if (status.canRetry) Icons.Default.Refresh else Icons.Default.Close,
                            contentDescription = stringResource(
                                if (status.canRetry) R.string.action_retry else R.string.action_dismiss,
                            ),
                        )
                    }
                }

                is Status.Error -> {
                    IconButton(onClick = onDismissError) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                        )
                    }
                }

                else -> Unit
            }
        },
    )
}

@Composable
private fun Status.toStatusText(): String {
    val context = LocalContext.current
    return when (this) {
        Status.Loading -> context.getString(R.string.status_loading_model)
        Status.Generating -> context.getString(R.string.status_generating)
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
        is Status.Ready -> {
            context.getString(
                if (isGpu) R.string.status_ready_gpu else R.string.status_ready_cpu,
            )
        }

        is Status.Error -> context.getString(R.string.status_error, message)
    }
}
