package com.example.oop.wearables.model

sealed class VitalType(val code: String, val unit: String) {
    data object HeartRate : VitalType("hr", "bpm")

    data object HrvRmssd : VitalType("hrv_rmssd", "ms")

    data object OxygenSaturation : VitalType("spo2", "%")

    data object BloodPressureSystolic : VitalType("bp_systolic", "mmHg")

    data object BloodPressureDiastolic : VitalType("bp_diastolic", "mmHg")

    data object BodyTemperature : VitalType("body_temp", "celsius")

    data object SkinTemperature : VitalType("skin_temp", "celsius")

    data object RespiratoryRate : VitalType("resp_rate", "breaths_per_min")

    companion object {
        val all: Set<VitalType> = setOf(
            HeartRate,
            HrvRmssd,
            OxygenSaturation,
            BloodPressureSystolic,
            BloodPressureDiastolic,
            BodyTemperature,
            SkinTemperature,
            RespiratoryRate,
        )

        val allClinical: Set<VitalType> = all

        fun fromCode(code: String): VitalType? = all.firstOrNull { it.code == code }
    }
}
