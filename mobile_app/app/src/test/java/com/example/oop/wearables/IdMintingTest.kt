package com.example.oop.wearables

import com.example.oop.wearables.data.IdMinting
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IdMintingTest {

    @Test
    fun `mint is deterministic for identical inputs`() {
        val a = IdMinting.mint(VitalType.HeartRate, 1000L, WearableSource.HEALTH_CONNECT, "Watch", 72.0)
        val b = IdMinting.mint(VitalType.HeartRate, 1000L, WearableSource.HEALTH_CONNECT, "Watch", 72.0)
        assertEquals(a, b)
    }

    @Test
    fun `mint varies when any field differs`() {
        val base = IdMinting.mint(VitalType.HeartRate, 1000L, WearableSource.HEALTH_CONNECT, "Watch", 72.0)

        assertNotEquals(
            base,
            IdMinting.mint(VitalType.HrvRmssd, 1000L, WearableSource.HEALTH_CONNECT, "Watch", 72.0),
        )
        assertNotEquals(
            base,
            IdMinting.mint(VitalType.HeartRate, 1001L, WearableSource.HEALTH_CONNECT, "Watch", 72.0),
        )
        assertNotEquals(
            base,
            IdMinting.mint(VitalType.HeartRate, 1000L, WearableSource.MOCK, "Watch", 72.0),
        )
        assertNotEquals(
            base,
            IdMinting.mint(VitalType.HeartRate, 1000L, WearableSource.HEALTH_CONNECT, "Ring", 72.0),
        )
        assertNotEquals(
            base,
            IdMinting.mint(VitalType.HeartRate, 1000L, WearableSource.HEALTH_CONNECT, "Watch", 73.0),
        )
    }

    @Test
    fun `mint produces 64 char sha256 hex`() {
        val id = IdMinting.mint(VitalType.HeartRate, 0L, WearableSource.MOCK, null, 0.0)
        assertEquals(64, id.length)
    }
}
