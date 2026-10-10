package com.example.catnav.data

data class Tracker(
    val trackerId: Long,
    val catName: String? = null,
    val state: String = "UNKNOWN",
    val batteryMillivolts: Int? = null,
    val lowBatteryLockout: Boolean = false,
    val rssi: Int? = null,
    val snr: Double? = null,
    val lastSeenAtMs: Long? = null,
    val lastSyncAtMs: Long? = null,
    val lastFetchRequestedAtMs: Long? = null,
    val registered: Boolean = true,
    val chargeNotificationSent: Boolean = false
) {
    val displayName: String
        get() = catName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Tracker 0x${trackerId.toString(16).uppercase().padStart(8, '0')}"

    val isActive: Boolean
        get() = registered && (
            state.equals("ACTIVE", ignoreCase = true) ||
                state.equals("AWAKE", ignoreCase = true)
            )
}

data class LocationRecord(
    val trackerId: Long,
    val recordSequence: Long,
    val utcSeconds: Long,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val receivedAtMs: Long
) {
    val latitude: Double
        get() = latitudeE7 / 10_000_000.0

    val longitude: Double
        get() = longitudeE7 / 10_000_000.0

    val timestampMs: Long
        get() = if (utcSeconds > 0L) utcSeconds * 1000L else receivedAtMs
}

data class GatewayJob(
    val jobId: String,
    val trackerId: Long,
    val command: String,
    val status: String,
    val detail: String?,
    val partial: Boolean = false,
    val pendingRecords: Long? = null,
    val acknowledgedChunks: Long? = null,
    val createdAtMs: Long,
    val handled: Boolean = false
) {
    val isTerminal: Boolean
        get() = status in setOf("COMPLETED", "FAILED", "TIMED_OUT")
}

data class TrackerSetting(
    val id: Int,
    val key: String,
    val title: String,
    val unit: String,
    val minimum: Long,
    val maximum: Long,
    val defaultApiValue: Long
) {
    fun displayValue(apiValue: Long): Long = apiValue

    fun apiValue(displayValue: Long): Long = displayValue
}

object TrackerSettings {
    val all = listOf(
        TrackerSetting(1, "sampleInterval", "Sample interval", "min", 1, 127, 1),
        TrackerSetting(2, "gpsTimeout", "GPS timeout", "sec", 5, 120, 45),
        TrackerSetting(4, "distanceThreshold", "Distance threshold", "m", 0, 127, 20),
        TrackerSetting(5, "criticalBattery", "Critical battery voltage", "mV", 3_000, 4_200, 3_300),
        TrackerSetting(6, "txPower", "Transmit power", "dBm", 2, 10, 10),
        TrackerSetting(7, "dormantSleep", "Dormant sleep interval", "min", 1, 59, 15),
        TrackerSetting(8, "radioListen", "Radio listen window", "sec", 3, 30, 6),
        TrackerSetting(9, "gpsColdStartTimeout", "GPS cold-start timeout", "sec", 5, 255, 255)
    )

    fun byId(id: Int): TrackerSetting? = all.firstOrNull { it.id == id }

    fun validConfiguration(values: Map<Int, Long>): Boolean {
        if (values.keys.any { byId(it) == null }) return false
        for (setting in all) {
            val apiValue = values[setting.id] ?: setting.defaultApiValue
            if (apiValue !in setting.minimum..setting.maximum) return false
        }
        val dormantSeconds = (values[7] ?: 15L) * 60L
        val listenSeconds = values[8] ?: 6L
        return listenSeconds < dormantSeconds
    }
}
