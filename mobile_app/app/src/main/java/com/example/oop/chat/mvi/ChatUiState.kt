package com.example.oop.chat.mvi

import android.net.Uri
import com.example.oop.stream.CaptureSummary
import com.example.oop.transcription.TranscriptUtterance
import com.example.oop.transcription.TranscriptionStatus

sealed class Status {
    data object Loading : Status()

    data class OntologyInstalling(
        val file: String,
        val bytes: Long,
        val total: Long,
    ) : Status()

    data class OntologyFailed(val message: String) : Status()

    data class Ready(val isGpu: Boolean) : Status()

    data object Generating : Status()

    data object Classifying : Status()

    data object NeedsToken : Status()

    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : Status()

    data class DownloadFailed(val message: String, val canRetry: Boolean) : Status()

    data object ModelMissing : Status()

    data class Error(val message: String) : Status()
}

data class ChatUiState(
    val status: Status = Status.Loading,
    val isClassificationEnabled: Boolean = false,
    val caption: ClassifiedCaption? = null,
    val lastCapture: CaptureSummary? = null,
    val captureThumbnails: Map<Long, TranscriptCaptureThumbnail> = emptyMap(),
    val transcript: List<TranscriptUtterance> = emptyList(),
    val livePartial: String = "",
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.Idle,
) {

    val isModelReady: Boolean
        get() = when (status) {
            is Status.Ready,
            Status.Classifying,
            -> true

            else -> false
        }
}

data class TranscriptCaptureThumbnail(
    val jpegUri: Uri,
    val bytes: ByteArray,
    val capturedAt: Long,
    val matchedPhrase: String,
    val pointOfInterest: String,
)
