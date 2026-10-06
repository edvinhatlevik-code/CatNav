package com.example.catnav.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

data class BatteryReport(
    val millivolts: Int?,
    val lowBatteryLockout: Boolean
)

class GatewayClient(private val preferences: AppPreferences) {
    fun gatewayStatus(): JSONObject = requestJson("$API_PREFIX/status")

    fun trackers(): List<Tracker> {
        val root = requestJson("$API_PREFIX/trackers")
        val trackerArray = root.arrayOrNull("trackers", "devices")
            ?: throw IOException("Gateway tracker response did not contain a tracker list.")
        return buildList {
            for (index in 0 until trackerArray.length()) {
                val tracker = trackerArray.optJSONObject(index) ?: continue
                add(tracker.toTracker())
            }
        }
    }

    fun registerTracker(trackerId: Long) {
        require(trackerId in 1L..0xFFFF_FFFFL) { "Tracker ID must be a non-zero 32-bit value." }
        requestJson(
            "$API_PREFIX/trackers",
            method = "POST",
            body = JSONObject().put("trackerId", trackerId)
        )
    }

    fun battery(trackerId: Long): BatteryReport {
        val root = requestJson("$API_PREFIX/trackers/$trackerId/battery")
        val report = root.optJSONObject("battery") ?: root
        val lowBatteryLockout = report.firstBoolean(
            "lowBatteryLockout",
            "lowBattery",
            "low_battery_lockout",
            "lockout"
        ) ?: throw IOException("Gateway battery response did not include lockout state.")
        return BatteryReport(
            millivolts = report.firstInt("batteryMillivolts", "millivolts", "battery_mv"),
            lowBatteryLockout = lowBatteryLockout
        )
    }

    fun queueCommand(
        trackerId: Long,
        command: String,
        settingId: Int? = null,
        value: Long? = null
    ): GatewayJob {
        val normalizedCommand = command.uppercase()
        require(normalizedCommand in setOf("WAKE", "SLEEP", "FETCH", "GET_CONFIG", "SET_CONFIG")) {
            "Unsupported tracker command."
        }
        val request = JSONObject().put("trackerId", trackerId).put("command", normalizedCommand)
        if (normalizedCommand == "SET_CONFIG") {
            require(settingId != null && value != null) { "SET_CONFIG needs a setting ID and value." }
            request.put("settingId", settingId)
            request.put("value", value)
        }

        val response = requestJson("$API_PREFIX/jobs", method = "POST", body = request)
        val jobId = response.firstString("jobId", "id")
            ?: throw IOException("Gateway accepted the command without returning a job ID.")
        return GatewayJob(
            jobId = jobId,
            trackerId = trackerId,
            command = normalizedCommand,
            status = response.firstString("status")?.uppercase() ?: "QUEUED",
            detail = response.firstString("detail", "message"),
            createdAtMs = System.currentTimeMillis()
        )
    }

    fun job(job: GatewayJob): GatewayJob {
        val response = requestJson("$API_PREFIX/jobs/${Uri.encode(job.jobId)}")
        return response.toJob(job)
    }

    fun fetchLocations(trackerId: Long): List<LocationRecord> {
        val records = mutableListOf<LocationRecord>()
        var offset = 0
        while (records.size < MAX_GATEWAY_LOCATIONS) {
            val root = requestJson("$API_PREFIX/trackers/$trackerId/locations?offset=$offset&limit=$PAGE_SIZE")
            val page = root.arrayOrNull("locations", "records", "items")
                ?: throw IOException("Gateway location response did not contain a location list.")
            val pageRecords = buildList {
                for (index in 0 until page.length()) {
                    val entry = page.optJSONObject(index) ?: continue
                    add(entry.toLocation(trackerId))
                }
            }
            records.addAll(pageRecords)
            if (page.length() < PAGE_SIZE || pageRecords.isEmpty()) break
            offset += page.length()
        }
        return records.take(MAX_GATEWAY_LOCATIONS)
    }

    fun configSnapshot(trackerId: Long): Map<Int, Long> {
        val root = requestJson("$API_PREFIX/trackers/$trackerId/config")
        val reported = root.optJSONObject("reported")
            ?: root.optJSONObject("current")
            ?: root.optJSONObject("runtime")
            ?: root.optJSONObject("runtimeConfig")
            ?: root.optJSONObject("latest")
            ?: root.optJSONObject("latestConfig")
            ?: root.optJSONObject("received")
            ?: root.optJSONObject("configuration")
            ?: root.optJSONObject("config")
            ?: root
        val values = mutableMapOf<Int, Long>()
        for (setting in TrackerSettings.all) {
            for (alias in settingAliases(setting.id)) {
                if (!reported.has(alias) || reported.isNull(alias)) continue
                val rawValue = reported.optLong(alias, Long.MIN_VALUE)
                if (rawValue == Long.MIN_VALUE) continue
                val wireMinutes = alias.contains("minutes", ignoreCase = true)
                values[setting.id] = if (wireMinutes && setting.apiSecondsPerDisplayUnit > 1L) {
                    rawValue * setting.apiSecondsPerDisplayUnit
                } else {
                    rawValue
                }
                break
            }
        }
        return values
    }

    fun openEventsConnection(): HttpURLConnection {
        val connection = openConnection("$API_PREFIX/events", readTimeoutMs = 0).apply {
            setRequestProperty("Accept", "text/event-stream")
        }
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val message = readError(connection)
            connection.disconnect()
            throw GatewayException("Gateway returned HTTP $responseCode: $message")
        }
        return connection
    }

    fun alerts(): List<JSONObject> {
        val root = requestJson("$API_PREFIX/alerts")
        val alertArray = root.arrayOrNull("alerts", "lockouts")
            ?: throw IOException("Gateway alert response did not contain an alert list.")
        return buildList {
            for (index in 0 until alertArray.length()) {
                val alert = alertArray.optJSONObject(index) ?: continue
                add(alert)
            }
        }
    }

    private fun requestJson(
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject {
        val connection = openConnection(path).apply {
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                readError(connection)
            }
            if (responseCode !in 200..299) {
                throw GatewayException("Gateway returned HTTP $responseCode: ${responseBody.take(MAX_ERROR_CHARS)}")
            }
            return if (responseBody.isBlank()) {
                JSONObject()
            } else {
                try {
                    JSONObject(responseBody)
                } catch (exception: JSONException) {
                    throw GatewayException("Gateway returned invalid JSON.", exception)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(path: String, readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS): HttpURLConnection {
        val token = preferences.apiToken
        if (token.isBlank()) throw GatewayException("Add the gateway API token in Settings to connect.")
        val baseUrl = normalizedBaseUrl(preferences.gatewayBaseUrl)
        return (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Authorization", token)
        }
    }

    fun normalizedBaseUrl(rawValue: String): String {
        var value = rawValue.trim().trimEnd('/')
        if (value.isBlank()) value = AppPreferences.DEFAULT_GATEWAY_URL
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "http://$value"
        }
        val url = try {
            URL(value)
        } catch (exception: MalformedURLException) {
            throw GatewayException("Enter a valid gateway hostname or IP address.", exception)
        }
        if (url.host.isBlank() || (url.protocol != "http" && url.protocol != "https") ||
            url.path.isNotEmpty() || url.query != null || url.ref != null
        ) {
            throw GatewayException("Use a gateway URL such as http://cat-gateway.local or http://192.168.1.20.")
        }
        return "${url.protocol}://${url.authority}"
    }

    private fun readError(connection: HttpURLConnection): String =
        connection.errorStream
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()

    private fun JSONObject.toTracker(): Tracker {
        val batteryObject = optJSONObject("battery")
        return Tracker(
            trackerId = firstLong("trackerId", "tracker_id", "id")
                ?: throw IOException("Gateway returned a tracker without an ID."),
            state = firstString("state", "trackerState", "status")?.uppercase() ?: "UNKNOWN",
            batteryMillivolts = firstInt("batteryMillivolts", "battery_mv", "millivolts")
                ?: batteryObject?.firstInt("batteryMillivolts", "millivolts", "battery_mv"),
            lowBatteryLockout = firstBoolean("lowBatteryLockout", "low_battery_lockout", "lowBattery")
                ?: batteryObject?.firstBoolean("lowBatteryLockout", "lowBattery", "lockout")
                ?: false,
            rssi = firstInt("rssi", "latestRssi"),
            snr = firstDouble("snr", "latestSnr"),
            lastSeenAtMs = firstLong("lastSeenAt", "lastSeen", "last_seen", "lastSeenEpoch")
                ?.toEpochMillis(),
            registered = true
        )
    }

    private fun JSONObject.toLocation(trackerId: Long): LocationRecord {
        val sequence = firstLong("recordSequence", "record_sequence", "sequence")
            ?: throw IOException("Gateway returned a location without a record sequence.")
        val utcSeconds = firstLong("utcSeconds", "utc_seconds", "timestampSeconds") ?: 0L
        val latitude = coordinateE7("latitudeE7", "latitude_e7", "latitude")
        val longitude = coordinateE7("longitudeE7", "longitude_e7", "longitude")
        val receivedAt = firstLong("receivedAt", "received_at", "receivedAtMs")
            ?.toEpochMillis() ?: System.currentTimeMillis()
        if (latitude !in -900_000_000..900_000_000 || longitude !in -1_800_000_000..1_800_000_000) {
            throw IOException("Gateway returned an invalid location for tracker $trackerId.")
        }
        return LocationRecord(
            trackerId = trackerId,
            recordSequence = sequence,
            utcSeconds = utcSeconds,
            latitudeE7 = latitude,
            longitudeE7 = longitude,
            receivedAtMs = receivedAt
        )
    }

    private fun JSONObject.toJob(previous: GatewayJob): GatewayJob {
        val result = optJSONObject("result") ?: optJSONObject("data") ?: this
        return previous.copy(
            status = firstString("status")?.uppercase() ?: previous.status,
            detail = firstString("detail", "error", "message") ?: previous.detail,
            partial = result.optBoolean("partial", optBoolean("partial", previous.partial)),
            pendingRecords = result.firstLong("pendingRecords", "pending_records")
                ?: firstLong("pendingRecords", "pending_records")
                ?: previous.pendingRecords,
            acknowledgedChunks = result.firstLong("acknowledgedChunks", "acknowledged_chunks")
                ?: firstLong("acknowledgedChunks", "acknowledged_chunks")
                ?: previous.acknowledgedChunks
        )
    }

    private fun JSONObject.coordinateE7(e7Name: String, alternateE7Name: String, decimalName: String): Int {
        if (has(e7Name) || has(alternateE7Name)) {
            val value = firstLong(e7Name, alternateE7Name)
                ?: throw IOException("Gateway returned a malformed $e7Name coordinate.")
            return value.toInt()
        }
        val value = firstDouble(decimalName)
            ?: throw IOException("Gateway returned a location without $decimalName.")
        return if (kotlin.math.abs(value) <= 180.0) {
            (value * 10_000_000.0).toInt()
        } else {
            value.toInt()
        }
    }

    private fun Long.toEpochMillis(): Long = if (this < 10_000_000_000L) this * 1_000L else this

    private fun settingAliases(id: Int): List<String> = when (id) {
        1 -> listOf("sampleIntervalSeconds", "sample_interval_seconds", "sampleIntervalMinutes", "sample_interval_minutes", "sampleInterval")
        2 -> listOf("autoSleepSeconds", "auto_sleep_seconds", "autoSleepMinutes", "auto_sleep_minutes", "autoSleep")
        3 -> listOf("gpsTimeoutSeconds", "gps_timeout_seconds", "gpsTimeout")
        4 -> listOf("batteryIntervalSeconds", "battery_interval_seconds", "batteryIntervalMinutes", "battery_interval_minutes", "batteryInterval")
        5 -> listOf("distanceThresholdMeters", "distance_threshold_meters", "distanceThreshold")
        6 -> listOf("criticalBatteryMillivolts", "critical_battery_millivolts", "criticalBattery")
        7 -> listOf("txPowerDbm", "tx_power_dbm", "txPower")
        8 -> listOf("dormantSleepSeconds", "dormant_sleep_seconds", "dormantSleepMinutes", "dormant_sleep_minutes", "dormantSleep")
        9 -> listOf("radioListenSeconds", "radio_listen_seconds", "radioListen")
        else -> emptyList()
    }

    private fun JSONObject.arrayOrNull(vararg keys: String): JSONArray? {
        for (key in keys) optJSONArray(key)?.let { return it }
        return null
    }

    private fun JSONObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value is Number) return value.toString()
        }
        return null
    }

    private fun JSONObject.firstLong(vararg keys: String): Long? {
        for (key in keys) {
            val value = opt(key)
            when (value) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? =
        firstLong(*keys)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private fun JSONObject.firstDouble(vararg keys: String): Double? {
        for (key in keys) {
            val value = opt(key)
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstBoolean(vararg keys: String): Boolean? {
        for (key in keys) {
            when (val value = opt(key)) {
                is Boolean -> return value
                is Number -> return value.toInt() != 0
                is String -> when (value.lowercase()) {
                    "true", "1", "active", "low" -> return true
                    "false", "0", "normal", "ok" -> return false
                }
            }
        }
        return null
    }

    companion object {
        private const val API_PREFIX = "/api/v1"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MS = 10_000
        private const val PAGE_SIZE = 100
        private const val MAX_GATEWAY_LOCATIONS = 1_024
        private const val MAX_ERROR_CHARS = 300
    }
}

class GatewayException(message: String, cause: Throwable? = null) : IOException(message, cause)
