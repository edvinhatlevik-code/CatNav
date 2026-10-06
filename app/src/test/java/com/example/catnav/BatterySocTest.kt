package com.example.catnav

import com.example.catnav.data.BatterySoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySocTest {
    @Test
    fun criticalVoltageDefinesZeroAndFullChargeDefinesOneHundred() {
        assertEquals(0, BatterySoc.percent(3_300, 3_300))
        assertEquals(100, BatterySoc.percent(4_200, 3_300))
        assertEquals(0, BatterySoc.percent(3_000, 3_300))
    }

    @Test
    fun changingCriticalVoltageRecalculatesTheCurve() {
        val defaultCurve = BatterySoc.percent(3_600, 3_300) ?: error("Expected battery percentage")
        val raisedThreshold = BatterySoc.percent(3_600, 3_600)

        assertEquals(0, raisedThreshold)
        assertTrue(defaultCurve > 0)
    }

    @Test
    fun unknownBatteryVoltageHasNoPercentage() {
        assertNull(BatterySoc.percent(null, 3_300))
    }
}
