package com.example.oop.chat.mvi

sealed interface ChatIntent {
    data object LoadModel : ChatIntent

    data object StartDownload : ChatIntent

    data class SubmitToken(val value: String) : ChatIntent

    data object CancelDownload : ChatIntent

    data class ToggleClassification(val enabled: Boolean) : ChatIntent

    data object StartTranscription : ChatIntent

    data object StopTranscription : ChatIntent

    data object CapturePhoto : ChatIntent

    data object ResetConversation : ChatIntent

    data object DismissError : ChatIntent
}
