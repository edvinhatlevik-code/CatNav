package com.example.catnav

import com.example.catnav.data.Tracker
import com.example.catnav.data.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSettingsTest {
    @Test
    fun trackerUsesCatNameAsDisplayNameAndFallsBackToItsId() {
        assertEquals("Luna", Tracker(trackerId = 1L, catName = " Luna ").displayName)
        assertEquals("Tracker 0x00000001", Tracker(trackerId = 1L).displayName)
    }

    @Test
    fun powerSavingIsANonActiveTrackerMode() {
        assertFalse(Tracker(trackerId = 1L, state = "POWER_SAVING").isActive)
    }

    @Test
    fun gatewayApiValuesUseTheDisplayedUnits() {
        val sampleInterval = TrackerSettings.byId(1) ?: error("Missing sample interval setting")
        val dormantSleep = TrackerSettings.byId(7) ?: error("Missing dormant sleep setting")

        assertEquals(3, sampleInterval.apiValue(3))
        assertEquals(3, sampleInterval.displayValue(3))
        assertEquals(15, dormantSleep.apiValue(15))
        assertEquals(15, dormantSleep.displayValue(15))
    }

    @Test
    fun settingsMatchTheCurrentGatewayIds() {
        assertEquals(listOf(1, 2, 4, 5, 6, 7, 8, 9), TrackerSettings.all.map { it.id })
        assertEquals(null, TrackerSettings.byId(3))
    }

    @Test
    fun defaultConfigurationIsValid() {
        val defaults = TrackerSettings.all.associate { it.id to it.defaultApiValue }

        assertTrue(TrackerSettings.validConfiguration(defaults))
    }

    @Test
    fun radioListenWindowAndGpsColdStartUseCurrentRanges() {
        val values = TrackerSettings.all.associate { it.id to it.defaultApiValue }.toMutableMap()
        values[7] = 1
        values[8] = 30
        assertTrue(TrackerSettings.validConfiguration(values))

        values[8] = 31
        assertFalse(TrackerSettings.validConfiguration(values))

        values[8] = 6
        values[9] = 256
        assertFalse(TrackerSettings.validConfiguration(values))
    }

    @Test
    fun retiredSettingIdCannotBeSaved() {
        val values = TrackerSettings.all.associate { it.id to it.defaultApiValue }.toMutableMap()
        values[3] = 45

        assertFalse(TrackerSettings.validConfiguration(values))
    }
}
