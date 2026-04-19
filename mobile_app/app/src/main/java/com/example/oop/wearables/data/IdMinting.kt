package com.example.oop.wearables.data

import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import java.security.MessageDigest

internal object IdMinting {

    fun mint(
        type: VitalType,
        timestampEpochMs: Long,
        source: WearableSource,
        device: String?,
        value: Double,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val payload = buildString {
            append(type.code)
            append('|')
            append(timestampEpochMs)
            append('|')
            append(source.name)
            append('|')
            append(device ?: "")
            append('|')
            append(value.toRawBits())
        }
        val bytes = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    fun mintFor(sample: VitalSample): String = mint(
        type = sample.type,
        timestampEpochMs = sample.timestamp.toEpochMilli(),
        source = sample.source,
        device = sample.device,
        value = sample.value,
    )
}
