package com.example.oop.stream

import android.net.Uri
import com.example.oop.chat.mvi.ClassifiedCaption
import kotlinx.serialization.Serializable

@Serializable
data class TriggerContext(
    val category: String,
    val matchedPhrase: String,
    val pointOfInterest: String,
    val sourceUtteranceId: Long,
    val sourceText: String,
)

data class CaptureSummary(
    val jpegUri: Uri,
    val sidecarUri: Uri,
    val capturedAt: Long,
    val caption: ClassifiedCaption?,
    val trigger: TriggerContext? = null,
    val thumbnailBytes: ByteArray? = null,
)
