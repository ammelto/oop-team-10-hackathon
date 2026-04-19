package com.example.oop.wearables.mvi

import com.example.oop.wearables.model.SourceStatus
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import java.time.Instant

data class WearablesUiState(
    val sources: Map<WearableSource, SourceStatus> = emptyMap(),
    val enabledSources: Set<WearableSource> = emptySet(),
    val latest: Map<VitalType, VitalSample> = emptyMap(),
    val streaming: Boolean = false,
    val error: String? = null,
    val missingPermissions: Set<String> = emptySet(),
    val clockTick: Instant = Instant.EPOCH,
)
