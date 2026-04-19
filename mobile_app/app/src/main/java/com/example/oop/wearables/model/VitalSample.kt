package com.example.oop.wearables.model

import java.time.Instant

data class VitalSample(
    val id: String,
    val timestamp: Instant,
    val type: VitalType,
    val value: Double,
    val source: WearableSource,
    val device: String? = null,
    val providerRecordId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
