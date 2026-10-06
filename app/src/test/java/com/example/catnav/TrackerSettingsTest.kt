package com.example.catnav

import com.example.catnav.data.TrackerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSettingsTest {
    @Test
    fun minuteSettingsConvertToGatewayApiSeconds() {
        val sampleInterval = TrackerSettings.byId(1) ?: error("Missing sample interval setting")

        assertEquals(180, sampleInterval.apiValue(3))
        assertEquals(3, sampleInterval.displayValue(180))
    }

    @Test
    fun defaultConfigurationIsValid() {
        val defaults = TrackerSettings.all.associate { it.id to it.defaultApiValue }

        assertTrue(TrackerSettings.validConfiguration(defaults))
    }

    @Test
    fun dormantListenWindowMustBeShorterThanRadioOffInterval() {
        val values = TrackerSettings.all.associate { it.id to it.defaultApiValue }.toMutableMap()
        values[8] = 60
        values[9] = 30
        assertTrue(TrackerSettings.validConfiguration(values))

        values[9] = 60
        assertFalse(TrackerSettings.validConfiguration(values))
    }

    @Test
    fun minuteSettingCannotBeSavedWithPartialMinutes() {
        val values = TrackerSettings.all.associate { it.id to it.defaultApiValue }.toMutableMap()
        values[1] = 61

        assertFalse(TrackerSettings.validConfiguration(values))
    }
}
