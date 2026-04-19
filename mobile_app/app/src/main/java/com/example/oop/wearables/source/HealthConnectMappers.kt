package com.example.oop.wearables.source

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.example.oop.wearables.data.IdMinting
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import java.time.Instant

internal object HealthConnectMappers {

    private val packageLabels: Map<String, String> = mapOf(
        "com.sec.android.app.shealth" to "Samsung Health (Galaxy Watch)",
        "com.ouraring.oura" to "Oura Ring",
        "com.fitbit.FitbitMobile" to "Fitbit",
        "com.garmin.android.apps.connectmobile" to "Garmin Connect",
        "com.whoop" to "Whoop",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.google.android.apps.healthdata" to "Health Connect",
    )

    fun toSamples(record: Record): List<VitalSample> {
        return when (record) {
            is HeartRateRecord -> fromHeartRate(record)
            is HeartRateVariabilityRmssdRecord -> listOfNotNull(fromHrv(record))
            is OxygenSaturationRecord -> listOfNotNull(fromSpo2(record))
            is BloodPressureRecord -> fromBp(record)
            is BodyTemperatureRecord -> listOfNotNull(fromBodyTemp(record))
            is SkinTemperatureRecord -> fromSkinTemp(record)
            is RespiratoryRateRecord -> listOfNotNull(fromRespRate(record))
            else -> emptyList()
        }
    }

    private fun fromHeartRate(record: HeartRateRecord): List<VitalSample> {
        val device = resolveDevice(record.metadata)
        return record.samples.map { sample ->
            buildSample(
                type = VitalType.HeartRate,
                timestamp = sample.time,
                value = sample.beatsPerMinute.toDouble(),
                device = device,
                providerRecordId = record.metadata.id,
                packageName = record.metadata.dataOrigin.packageName,
            )
        }
    }

    private fun fromHrv(record: HeartRateVariabilityRmssdRecord): VitalSample = buildSample(
        type = VitalType.HrvRmssd,
        timestamp = record.time,
        value = record.heartRateVariabilityMillis,
        device = resolveDevice(record.metadata),
        providerRecordId = record.metadata.id,
        packageName = record.metadata.dataOrigin.packageName,
    )

    private fun fromSpo2(record: OxygenSaturationRecord): VitalSample = buildSample(
        type = VitalType.OxygenSaturation,
        timestamp = record.time,
        value = record.percentage.value,
        device = resolveDevice(record.metadata),
        providerRecordId = record.metadata.id,
        packageName = record.metadata.dataOrigin.packageName,
    )

    private fun fromBp(record: BloodPressureRecord): List<VitalSample> {
        val device = resolveDevice(record.metadata)
        return listOf(
            buildSample(
                type = VitalType.BloodPressureSystolic,
                timestamp = record.time,
                value = record.systolic.inMillimetersOfMercury,
                device = device,
                providerRecordId = record.metadata.id,
                packageName = record.metadata.dataOrigin.packageName,
            ),
            buildSample(
                type = VitalType.BloodPressureDiastolic,
                timestamp = record.time,
                value = record.diastolic.inMillimetersOfMercury,
                device = device,
                providerRecordId = record.metadata.id,
                packageName = record.metadata.dataOrigin.packageName,
            ),
        )
    }

    private fun fromBodyTemp(record: BodyTemperatureRecord): VitalSample = buildSample(
        type = VitalType.BodyTemperature,
        timestamp = record.time,
        value = record.temperature.inCelsius,
        device = resolveDevice(record.metadata),
        providerRecordId = record.metadata.id,
        packageName = record.metadata.dataOrigin.packageName,
    )

    private fun fromSkinTemp(record: SkinTemperatureRecord): List<VitalSample> {
        val baseline = record.baseline ?: return emptyList()
        val device = resolveDevice(record.metadata)
        return record.deltas.map { delta ->
            val absolute = baseline.inCelsius + delta.delta.inCelsius
            buildSample(
                type = VitalType.SkinTemperature,
                timestamp = delta.time,
                value = absolute,
                device = device,
                providerRecordId = record.metadata.id,
                packageName = record.metadata.dataOrigin.packageName,
                metadata = mapOf(
                    "baseline_celsius" to baseline.inCelsius.toString(),
                    "delta_celsius" to delta.delta.inCelsius.toString(),
                ),
            )
        }
    }

    private fun fromRespRate(record: RespiratoryRateRecord): VitalSample = buildSample(
        type = VitalType.RespiratoryRate,
        timestamp = record.time,
        value = record.rate,
        device = resolveDevice(record.metadata),
        providerRecordId = record.metadata.id,
        packageName = record.metadata.dataOrigin.packageName,
    )

    private fun resolveDevice(metadata: Metadata): String? {
        val device = metadata.device
        val deviceLabel = if (device != null) {
            val parts = listOfNotNull(device.manufacturer, device.model)
                .filter { it.isNotBlank() }
            if (parts.isNotEmpty()) parts.joinToString(" ") else null
        } else {
            null
        }
        return deviceLabel ?: packageLabels[metadata.dataOrigin.packageName]
            ?: metadata.dataOrigin.packageName.takeIf { it.isNotBlank() }
    }

    private fun buildSample(
        type: VitalType,
        timestamp: Instant,
        value: Double,
        device: String?,
        providerRecordId: String?,
        packageName: String,
        metadata: Map<String, String> = emptyMap(),
    ): VitalSample {
        val fullMetadata = if (packageName.isNotBlank()) {
            metadata + ("origin_package" to packageName)
        } else {
            metadata
        }
        return VitalSample(
            id = IdMinting.mint(
                type = type,
                timestampEpochMs = timestamp.toEpochMilli(),
                source = WearableSource.HEALTH_CONNECT,
                device = device,
                value = value,
            ),
            timestamp = timestamp,
            type = type,
            value = value,
            source = WearableSource.HEALTH_CONNECT,
            device = device,
            providerRecordId = providerRecordId,
            metadata = fullMetadata,
        )
    }
}
