package com.example.oop.wearables.mvi

import com.example.oop.wearables.model.WearableSource

sealed interface WearablesIntent {
    data object Refresh : WearablesIntent

    data object RequestHealthConnectPermissions : WearablesIntent

    data class SourceToggled(val source: WearableSource, val enabled: Boolean) : WearablesIntent

    data object StartStreaming : WearablesIntent

    data object StopStreaming : WearablesIntent

    data object DismissError : WearablesIntent

    data class PermissionsGranted(val granted: Set<String>) : WearablesIntent
}
