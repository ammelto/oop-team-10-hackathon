package com.example.oop.wearables.mvi

sealed interface WearablesEffect {
    data class LaunchHealthConnectPermissions(val permissions: Set<String>) : WearablesEffect

    data class ShowError(val message: String) : WearablesEffect
}
