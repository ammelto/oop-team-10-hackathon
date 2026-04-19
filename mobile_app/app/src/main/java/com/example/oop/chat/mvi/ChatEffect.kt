package com.example.oop.chat.mvi

import android.net.Uri

sealed interface ChatEffect {
    data class ScrollToBottom(val index: Int) : ChatEffect

    data class ShowError(val message: String) : ChatEffect

    data class PhotoCaptured(
        val jpegUri: Uri,
        val sidecarUri: Uri,
    ) : ChatEffect
}
