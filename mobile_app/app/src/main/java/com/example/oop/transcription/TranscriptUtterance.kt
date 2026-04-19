package com.example.oop.transcription

data class TranscriptUtterance(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val highlightedPhrases: List<String> = emptyList(),
)

sealed interface TranscriptionStatus {
    data object Idle : TranscriptionStatus

    data object Routing : TranscriptionStatus

    data object Listening : TranscriptionStatus

    data object Decoding : TranscriptionStatus

    data object UsingPhoneMic : TranscriptionStatus

    data object Error : TranscriptionStatus

    data class DownloadingModel(val bytesDownloaded: Long, val totalBytes: Long) : TranscriptionStatus
}
