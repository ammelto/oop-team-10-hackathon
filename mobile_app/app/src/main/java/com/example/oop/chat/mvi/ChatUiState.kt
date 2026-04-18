package com.example.oop.chat.mvi

import android.net.Uri
import com.example.oop.chat.model.ChatMessage

sealed class Status {
    data object Loading : Status()

    data class Ready(val isGpu: Boolean) : Status()

    data object Generating : Status()

    data object NeedsToken : Status()

    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : Status()

    data class DownloadFailed(val message: String, val canRetry: Boolean) : Status()

    data object ModelMissing : Status()

    data class Error(val message: String) : Status()
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val pendingImage: Uri? = null,
    val status: Status = Status.Loading,
) {
    val canSend: Boolean
        get() = when (status) {
            is Status.Loading,
            is Status.Generating,
            is Status.Downloading,
            is Status.NeedsToken,
            is Status.DownloadFailed,
            is Status.ModelMissing,
            -> false

            else -> input.isNotBlank() || pendingImage != null
        }
}
