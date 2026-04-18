package com.example.oop.chat.mvi

sealed interface ChatEffect {
    data class ScrollToBottom(val index: Int) : ChatEffect

    data class ShowError(val message: String) : ChatEffect
}
