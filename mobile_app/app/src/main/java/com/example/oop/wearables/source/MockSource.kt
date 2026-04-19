package com.example.oop.wearables.source

import android.content.Context
import com.example.oop.wearables.data.IdMinting
import com.example.oop.wearables.model.SourceStatus
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class MockSource(private val random: Random = Random.Default) : WearableDataSource {

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Uninitialized)

    override val id: WearableSource = WearableSource.MOCK

    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    override suspend fun initialize(context: Context) {
        _status.value = SourceStatus.Ready
    }

    override fun vitals(): Flow<VitalSample> = channelFlow {
        _status.value = SourceStatus.Ready

        launch { emitPeriodic(channel, VitalType.HeartRate, intervalMs = 1_000L, base = 72.0, jitter = 6.0, breathing = true) }
        launch { emitPeriodic(channel, VitalType.HrvRmssd, intervalMs = 30_000L, base = 42.0, jitter = 8.0) }
        launch { emitPeriodic(channel, VitalType.OxygenSaturation, intervalMs = 10_000L, base = 97.0, jitter = 1.0, clampMin = 90.0, clampMax = 100.0) }
        launch { emitBloodPressure(channel) }
        launch { emitPeriodic(channel, VitalType.BodyTemperature, intervalMs = 60_000L, base = 36.7, jitter = 0.1) }
        launch { emitPeriodic(channel, VitalType.SkinTemperature, intervalMs = 60_000L, base = 34.2, jitter = 0.3) }
        launch { emitPeriodic(channel, VitalType.RespiratoryRate, intervalMs = 30_000L, base = 14.0, jitter = 1.0) }
    }

    override suspend fun close() {
        _status.value = SourceStatus.Uninitialized
    }

    private suspend fun emitPeriodic(
        channel: SendChannel<VitalSample>,
        type: VitalType,
        intervalMs: Long,
        base: Double,
        jitter: Double,
        breathing: Boolean = false,
        clampMin: Double = Double.NEGATIVE_INFINITY,
        clampMax: Double = Double.POSITIVE_INFINITY,
    ) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            val elapsed = (System.currentTimeMillis() - startedAt) / 1_000.0
            val breathingComponent = if (breathing) sin(2.0 * PI * elapsed / 5.0) * 1.5 else 0.0
            val value = (base + random.gaussian() * jitter + breathingComponent)
                .coerceIn(clampMin, clampMax)
            channel.send(buildSample(type, value))
            delay(intervalMs)
        }
    }

    private suspend fun emitBloodPressure(channel: SendChannel<VitalSample>) {
        while (true) {
            val sys = 118.0 + random.gaussian() * 4.0
            val dia = 78.0 + random.gaussian() * 3.0
            channel.send(buildSample(VitalType.BloodPressureSystolic, sys))
            channel.send(buildSample(VitalType.BloodPressureDiastolic, dia))
            delay(5 * 60 * 1000L)
        }
    }

    private fun buildSample(type: VitalType, value: Double): VitalSample {
        val timestamp = Instant.now()
        return VitalSample(
            id = IdMinting.mint(
                type = type,
                timestampEpochMs = timestamp.toEpochMilli(),
                source = WearableSource.MOCK,
                device = DEVICE_LABEL,
                value = value,
            ),
            timestamp = timestamp,
            type = type,
            value = value,
            source = WearableSource.MOCK,
            device = DEVICE_LABEL,
            providerRecordId = null,
            metadata = mapOf("generator" to "MockSource"),
        )
    }

    private fun Random.gaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(Double.MIN_VALUE)
        val u2 = nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    private companion object {
        const val DEVICE_LABEL = "Simulated"
    }
}
