package com.example.oop.wearables.data

import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

internal object VitalMapping {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val metadataSerializer =
        MapSerializer(String.serializer(), String.serializer())

    fun encodeMetadata(metadata: Map<String, String>): String {
        if (metadata.isEmpty()) return "{}"
        return json.encodeToString(metadataSerializer, metadata)
    }

    fun decodeMetadata(raw: String): Map<String, String> {
        if (raw.isBlank() || raw == "{}") return emptyMap()
        return runCatching { json.decodeFromString(metadataSerializer, raw) }
            .getOrDefault(emptyMap())
    }

    fun toEntity(sample: VitalSample): VitalEntity = VitalEntity(
        id = sample.id,
        timestampEpochMs = sample.timestamp.toEpochMilli(),
        type = sample.type.code,
        value = sample.value,
        unit = sample.type.unit,
        source = sample.source.name,
        device = sample.device,
        providerRecordId = sample.providerRecordId,
        metadataJson = encodeMetadata(sample.metadata),
    )

    fun fromEntity(entity: VitalEntity): VitalSample? {
        val type = VitalType.fromCode(entity.type) ?: return null
        val source = runCatching { WearableSource.valueOf(entity.source) }.getOrNull()
            ?: return null
        return VitalSample(
            id = entity.id,
            timestamp = Instant.ofEpochMilli(entity.timestampEpochMs),
            type = type,
            value = entity.value,
            source = source,
            device = entity.device,
            providerRecordId = entity.providerRecordId,
            metadata = decodeMetadata(entity.metadataJson),
        )
    }
}
