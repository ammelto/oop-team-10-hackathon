package com.example.oop.chat.mvi

import android.net.Uri

sealed interface ChatIntent {
    data object LoadModel : ChatIntent

    data object StartDownload : ChatIntent

    data class SubmitToken(val value: String) : ChatIntent

    data object CancelDownload : ChatIntent

    data class InputChanged(val text: String) : ChatIntent

    data class AttachPhoto(val uri: Uri) : ChatIntent

    data object ClearPendingPhoto : ChatIntent

    data object SendMessage : ChatIntent

    data object ResetConversation : ChatIntent

    data object DismissError : ChatIntent
}
