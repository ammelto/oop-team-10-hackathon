package com.example.oop.wearables.data

import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import java.time.Duration
import java.time.Instant

object VitalSummaries {

    data class TypeSummary(
        val type: VitalType,
        val count: Int,
        val min: Double,
        val max: Double,
        val mean: Double,
        val latest: Double,
        val latestAt: Instant,
        val devices: List<String>,
    )

    fun groupedByType(samples: List<VitalSample>): Map<VitalType, List<VitalSample>> =
        samples.groupBy { it.type }

    fun summarize(samples: List<VitalSample>): Map<VitalType, TypeSummary> {
        if (samples.isEmpty()) return emptyMap()
        return groupedByType(samples).mapValues { (type, group) ->
            val values = group.map { it.value }
            val latest = group.maxBy { it.timestamp.toEpochMilli() }
            TypeSummary(
                type = type,
                count = group.size,
                min = values.min(),
                max = values.max(),
                mean = values.average(),
                latest = latest.value,
                latestAt = latest.timestamp,
                devices = group.mapNotNull { it.device }.distinct(),
            )
        }
    }

    fun List<VitalSample>.summarizeForPrompt(reference: Instant = Instant.now()): String {
        if (isEmpty()) return "No vitals available."
        val summaries = summarize(this)
        return buildString {
            summaries.values.sortedBy { it.type.code }.forEachIndexed { index, summary ->
                if (index > 0) appendLine()
                val ageSeconds = Duration.between(summary.latestAt, reference).seconds
                append(summary.type.code.padEnd(14))
                append(": ")
                append(formatValue(summary.type, summary.latest))
                append(' ')
                append(summary.type.unit)
                append(" (min ")
                append(formatValue(summary.type, summary.min))
                append(", max ")
                append(formatValue(summary.type, summary.max))
                append(", mean ")
                append(formatValue(summary.type, summary.mean))
                append(", n=")
                append(summary.count)
                append(", ")
                append(ageSeconds)
                append("s ago")
                if (summary.devices.isNotEmpty()) {
                    append(", ")
                    append(summary.devices.joinToString("/"))
                }
                append(")")
            }
        }
    }

    private fun formatValue(type: VitalType, value: Double): String {
        val isInteger = when (type) {
            VitalType.HeartRate,
            VitalType.HrvRmssd,
            VitalType.BloodPressureSystolic,
            VitalType.BloodPressureDiastolic,
            VitalType.RespiratoryRate,
            -> true
            else -> false
        }
        return if (isInteger) {
            value.toInt().toString()
        } else {
            "%.1f".format(value)
        }
    }
}
