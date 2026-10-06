package com.example.catnav.data

import android.content.Context
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("catnav_preferences", Context.MODE_PRIVATE)

    var gatewayBaseUrl: String
        get() = preferences.getString(KEY_GATEWAY_URL, DEFAULT_GATEWAY_URL) ?: DEFAULT_GATEWAY_URL
        set(value) {
            preferences.edit { putString(KEY_GATEWAY_URL, value.trim()) }
        }

    var apiToken: String
        get() = preferences.getString(KEY_API_TOKEN, "").orEmpty()
        set(value) {
            preferences.edit { putString(KEY_API_TOKEN, value.trim()) }
        }

    var syncThresholdMinutes: Int
        get() = preferences.getInt(KEY_SYNC_THRESHOLD, 60)
        set(value) {
            preferences.edit { putInt(KEY_SYNC_THRESHOLD, value.coerceIn(5, 1_440)) }
        }

    var eventsEnabled: Boolean
        get() = preferences.getBoolean(KEY_EVENTS_ENABLED, true)
        set(value) {
            preferences.edit { putBoolean(KEY_EVENTS_ENABLED, value) }
        }

    var autoFetchEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_FETCH_ENABLED, true)
        set(value) {
            preferences.edit { putBoolean(KEY_AUTO_FETCH_ENABLED, value) }
        }

    var selectedTrackerId: Long?
        get() = if (preferences.contains(KEY_SELECTED_TRACKER)) {
            preferences.getLong(KEY_SELECTED_TRACKER, 0L)
        } else {
            null
        }
        set(value) {
            preferences.edit {
                if (value == null) remove(KEY_SELECTED_TRACKER) else putLong(KEY_SELECTED_TRACKER, value)
            }
        }

    fun configuration(trackerId: Long): Map<Int, Long> = TrackerSettings.all.associate { setting ->
        val key = configKey(trackerId, setting.id)
        setting.id to preferences.getLong(key, setting.defaultApiValue)
    }

    fun saveConfiguration(trackerId: Long, values: Map<Int, Long>) {
        preferences.edit {
            values.forEach { (id, value) -> putLong(configKey(trackerId, id), value) }
        }
    }

    private fun configKey(trackerId: Long, settingId: Int): String =
        "tracker_config_${trackerId}_$settingId"

    companion object {
        const val DEFAULT_GATEWAY_URL = "http://cat-gateway.local"
        private const val KEY_GATEWAY_URL = "gateway_base_url"
        private const val KEY_API_TOKEN = "gateway_api_token"
        private const val KEY_SYNC_THRESHOLD = "sync_threshold_minutes"
        private const val KEY_EVENTS_ENABLED = "events_enabled"
        private const val KEY_AUTO_FETCH_ENABLED = "auto_fetch_enabled"
        private const val KEY_SELECTED_TRACKER = "selected_tracker"
    }
}
