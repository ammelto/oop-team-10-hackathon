package com.example.oop.wearables

import com.example.oop.wearables.data.IdMinting
import com.example.oop.wearables.data.VitalSummaries
import com.example.oop.wearables.data.VitalSummaries.summarizeForPrompt
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VitalSummariesTest {

    private val reference: Instant = Instant.parse("2026-04-18T12:00:00Z")

    @Test
    fun `summarize returns empty when no samples`() {
        assertEquals(emptyMap<VitalType, VitalSummaries.TypeSummary>(), VitalSummaries.summarize(emptyList()))
    }

    @Test
    fun `summarize computes min max mean for heart rate`() {
        val samples = listOf(
            sample(VitalType.HeartRate, 60.0, offsetSeconds = -3),
            sample(VitalType.HeartRate, 70.0, offsetSeconds = -2),
            sample(VitalType.HeartRate, 80.0, offsetSeconds = -1),
        )
        val summary = VitalSummaries.summarize(samples)[VitalType.HeartRate]
        assertNotNull(summary)
        requireNotNull(summary)
        assertEquals(3, summary.count)
        assertEquals(60.0, summary.min, 0.0)
        assertEquals(80.0, summary.max, 0.0)
        assertEquals(70.0, summary.mean, 0.0)
        assertEquals(80.0, summary.latest, 0.0)
    }

    @Test
    fun `summarizeForPrompt includes device and unit`() {
        val samples = listOf(
            sample(VitalType.HeartRate, 72.0, offsetSeconds = -5, device = "Galaxy Watch7"),
            sample(VitalType.OxygenSaturation, 98.0, offsetSeconds = -4, device = "Galaxy Watch7"),
        )
        val rendered = samples.summarizeForPrompt(reference)
        assertTrue("has hr", rendered.contains("hr"))
        assertTrue("has bpm", rendered.contains("bpm"))
        assertTrue("has spo2", rendered.contains("spo2"))
        assertTrue("has Galaxy Watch7", rendered.contains("Galaxy Watch7"))
    }

    @Test
    fun `summarizeForPrompt handles empty list`() {
        assertEquals("No vitals available.", emptyList<VitalSample>().summarizeForPrompt(reference))
    }

    private fun sample(
        type: VitalType,
        value: Double,
        offsetSeconds: Long,
        device: String? = "TestDevice",
    ): VitalSample {
        val ts = reference.plusSeconds(offsetSeconds)
        return VitalSample(
            id = IdMinting.mint(type, ts.toEpochMilli(), WearableSource.MOCK, device, value),
            timestamp = ts,
            type = type,
            value = value,
            source = WearableSource.MOCK,
            device = device,
        )
    }
}
