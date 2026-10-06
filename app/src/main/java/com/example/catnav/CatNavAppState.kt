package com.example.catnav

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.catnav.data.AppPreferences
import com.example.catnav.data.CatNavDatabase
import com.example.catnav.data.DatabaseXmlTransfer
import com.example.catnav.data.GatewayClient
import com.example.catnav.data.GatewayDiscovery
import com.example.catnav.data.GatewayException
import com.example.catnav.data.GatewayJob
import com.example.catnav.data.GatewayJobProcessor
import com.example.catnav.data.Tracker
import com.example.catnav.data.TrackerSettings
import com.example.catnav.service.GatewayEventService
import java.io.IOException
import android.net.Uri
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class CatNavAppState(context: Context) {
    private val appContext = context.applicationContext
    val preferences = AppPreferences(appContext)
    val database = CatNavDatabase(appContext)
    private val gateway = GatewayClient(preferences)
    private val discovery = GatewayDiscovery(appContext)
    private val jobProcessor = GatewayJobProcessor(appContext)
    private val ioExecutor = Executors.newCachedThreadPool()
    private val monitorExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var trackers by mutableStateOf(database.trackers())
        private set
    var recentJobs by mutableStateOf(database.recentJobs())
        private set
    var totalLocations by mutableLongStateOf(database.locationCount())
        private set
    var gatewayConnected by mutableStateOf(false)
        private set
    var gatewayBaseUrl by mutableStateOf(preferences.gatewayBaseUrl)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var isDiscovering by mutableStateOf(false)
        private set
    var syncingTrackerIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    var isSyncingAll by mutableStateOf(false)
        private set
    var selectedTrackerId by mutableStateOf(preferences.selectedTrackerId)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var lastJobMonitorError: String? = null

    init {
        chooseSelectedTracker()
        monitorExecutor.scheduleWithFixedDelay(
            {
                if (preferences.apiToken.isNotBlank() && appInForeground) {
                    try {
                        jobProcessor.pollPendingJobs()
                        lastJobMonitorError = null
                    } catch (exception: IOException) {
                        Log.w(TAG, "Could not refresh tracker job status: ${exception.message}")
                        reportJobMonitorError(exception.message ?: "Could not update tracker job status.")
                    } catch (exception: IllegalStateException) {
                        Log.e(TAG, "Tracker job data could not be saved.", exception)
                        reportJobMonitorError(exception.message ?: "Tracker job data could not be saved.")
                    } catch (exception: SecurityException) {
                        Log.e(TAG, "Android denied tracker job network access.", exception)
                        reportJobMonitorError("Android denied tracker job network access.")
                    }
                }
                refreshLocalState()
            },
            JOB_POLL_INTERVAL_SECONDS,
            JOB_POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    fun onAppOpened() {
        if (preferences.eventsEnabled && preferences.apiToken.isNotBlank()) startEventService()
        refreshGateway(queueStaleFetches = true)
    }

    fun refreshGateway(queueStaleFetches: Boolean = false) {
        if (preferences.apiToken.isBlank()) {
            showMessage("Add the gateway API token in Settings to connect.")
            return
        }
        post {
            isRefreshing = true
            message = null
        }
        ioExecutor.execute {
            try {
                gateway.gatewayStatus()
                val remoteTrackers = refreshTrackersFromGateway()
                post {
                    gatewayConnected = true
                    isRefreshing = false
                }
                if (queueStaleFetches && preferences.autoFetchEnabled) {
                    queueStaleActiveTrackers(remoteTrackers)
                }
                refreshLocalState()
            } catch (exception: IOException) {
                post {
                    gatewayConnected = false
                    isRefreshing = false
                    message = exception.message ?: "Could not connect to the gateway."
                }
            } catch (exception: IllegalArgumentException) {
                post {
                    gatewayConnected = false
                    isRefreshing = false
                    message = exception.message ?: "Gateway settings are invalid."
                }
            }
        }
    }

    fun registerTracker(trackerId: Long) {
        if (trackerId !in 1L..0xFFFF_FFFFL) {
            showMessage("Enter a non-zero 32-bit tracker ID.")
            return
        }
        ioExecutor.execute {
            try {
                gateway.registerTracker(trackerId)
                val local = database.tracker(trackerId)
                database.upsertTracker(
                    (local ?: Tracker(trackerId = trackerId)).copy(registered = true)
                )
                post {
                    selectedTrackerId = trackerId
                    preferences.selectedTrackerId = trackerId
                    message = "Tracker ${trackerId.toString(16).uppercase()} added."
                }
                refreshGateway()
            } catch (exception: IOException) {
                showMessage(exception.message ?: "Could not register the tracker.")
            } catch (exception: IllegalArgumentException) {
                showMessage(exception.message ?: "Tracker ID is invalid.")
            }
        }
    }

    fun selectTracker(trackerId: Long?) {
        preferences.selectedTrackerId = trackerId
        selectedTrackerId = trackerId
    }

    fun queueCommand(trackerId: Long, command: String) {
        val normalizedCommand = command.uppercase()
        if (normalizedCommand !in setOf("WAKE", "SLEEP", "FETCH")) {
            showMessage("Choose a supported tracker action.")
            return
        }
        ioExecutor.execute {
            try {
                queueCommandBlocking(trackerId, normalizedCommand)
                showMessage("$normalizedCommand queued for tracker ${trackerId.toString(16).uppercase()}.")
            } catch (exception: IOException) {
                showMessage(exception.message ?: "Could not queue $normalizedCommand.")
            } catch (exception: IllegalArgumentException) {
                showMessage(exception.message ?: "The tracker command is invalid.")
            }
        }
    }

    fun queueBulkCommand(trackerIds: List<Long>, command: String) {
        val normalizedCommand = command.uppercase()
        if (trackerIds.isEmpty()) {
            showMessage("Select at least one tracker first.")
            return
        }
        if (normalizedCommand !in setOf("WAKE", "SLEEP", "FETCH")) {
            showMessage("Choose a supported tracker action.")
            return
        }
        ioExecutor.execute {
            var queued = 0
            val failures = mutableListOf<String>()
            for (trackerId in trackerIds.distinct()) {
                try {
                    queueCommandBlocking(trackerId, normalizedCommand)
                    queued++
                } catch (exception: IOException) {
                    failures.add("${trackerId.toString(16).uppercase()}: ${exception.message}")
                }
            }
            val result = buildString {
                append("$normalizedCommand queued for $queued tracker(s).")
                if (failures.isNotEmpty()) append(" Failed: ${failures.joinToString("; ")}")
            }
            showMessage(result)
        }
    }

    fun saveConfiguration(trackerId: Long, values: Map<Int, Long>): Boolean {
        if (!TrackerSettings.validConfiguration(values)) {
            showMessage("Configuration values are out of range or the listen window must be shorter than the dormant interval.")
            return false
        }
        preferences.saveConfiguration(trackerId, values)
        showMessage("Configuration saved on this phone.")
        return true
    }

    fun localConfiguration(trackerId: Long): Map<Int, Long> = preferences.configuration(trackerId)

    fun syncConfiguration(trackerId: Long) {
        startConfigSync(trackerId)
    }

    fun syncAllActiveConfigurations() {
        startConfigSync(null)
    }

    fun saveGatewaySettings(
        baseUrl: String,
        apiToken: String,
        syncThresholdMinutes: Int,
        autoFetch: Boolean,
        eventsEnabled: Boolean
    ) {
        try {
            val validatedBaseUrl = gateway.normalizedBaseUrl(baseUrl)
            require(syncThresholdMinutes in 5..1_440) { "Fetch interval must be between 5 and 1440 minutes." }
            preferences.gatewayBaseUrl = validatedBaseUrl
            gatewayBaseUrl = validatedBaseUrl
            preferences.apiToken = apiToken
            preferences.syncThresholdMinutes = syncThresholdMinutes
            preferences.autoFetchEnabled = autoFetch
            preferences.eventsEnabled = eventsEnabled
            if (eventsEnabled && apiToken.isNotBlank()) {
                startEventService()
            } else {
                appContext.stopService(Intent(appContext, GatewayEventService::class.java))
            }
            showMessage("Gateway settings saved.")
            if (apiToken.isNotBlank()) refreshGateway()
        } catch (exception: IllegalArgumentException) {
            showMessage(exception.message ?: "Gateway settings are invalid.")
        } catch (exception: GatewayException) {
            showMessage(exception.message ?: "Gateway address is invalid.")
        } catch (exception: SecurityException) {
            showMessage("Android blocked the background monitoring service.")
        }
    }

    fun exportDatabase(uri: Uri) {
        ioExecutor.execute {
            try {
                val output = appContext.contentResolver.openOutputStream(uri)
                    ?: throw IOException("Could not open the selected XML file for writing.")
                output.use { DatabaseXmlTransfer.export(database, it) }
                showMessage("CatNav history exported as XML.")
            } catch (exception: IOException) {
                showMessage(exception.message ?: "Could not export the local database.")
            } catch (exception: IllegalArgumentException) {
                showMessage(exception.message ?: "The selected export destination is invalid.")
            }
        }
    }

    fun importDatabase(uri: Uri) {
        ioExecutor.execute {
            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open the selected XML file for reading.")
                val result = input.use { DatabaseXmlTransfer.import(database, it) }
                refreshLocalState()
                showMessage("Imported ${result.locations} new locations for ${result.trackers} tracker(s).")
            } catch (exception: IOException) {
                showMessage(exception.message ?: "Could not import the XML file.")
            } catch (exception: IllegalArgumentException) {
                showMessage(exception.message ?: "The selected file is not a valid CatNav export.")
            } catch (exception: IllegalStateException) {
                showMessage(exception.message ?: "The XML contains conflicting location data.")
            } catch (exception: org.xmlpull.v1.XmlPullParserException) {
                showMessage(exception.message ?: "The selected XML file could not be parsed.")
            }
        }
    }

    fun discoverGateway() {
        if (isDiscovering) return
        post {
            isDiscovering = true
            message = null
        }
        try {
            discovery.discover(
                onFound = { endpoint ->
                    val host = if (endpoint.host.contains(':') && !endpoint.host.startsWith("[")) {
                        "[${endpoint.host}]"
                    } else {
                        endpoint.host
                    }
                    preferences.gatewayBaseUrl = "http://$host:${endpoint.port}"
                    post {
                        gatewayBaseUrl = preferences.gatewayBaseUrl
                        isDiscovering = false
                        message = "Gateway found at ${preferences.gatewayBaseUrl}."
                    }
                    refreshGateway()
                },
                onFailure = { reason ->
                    post {
                        isDiscovering = false
                        message = reason
                    }
                }
            )
        } catch (exception: IllegalStateException) {
            post {
                isDiscovering = false
                message = exception.message ?: "Network service discovery is unavailable."
            }
        }
    }

    fun clearMessage() {
        message = null
    }

    fun notificationPermissionDenied() {
        showMessage("Tracker alerts need notification permission. Enable CatNav notifications in Android Settings.")
    }

    fun refreshLocalState() {
        val localTrackers = database.trackers()
        val localJobs = database.recentJobs()
        val count = database.locationCount()
        post {
            trackers = localTrackers
            recentJobs = localJobs
            totalLocations = count
            chooseSelectedTracker()
        }
    }

    fun locationsFor(trackerId: Long?): List<com.example.catnav.data.LocationRecord> =
        trackerId?.let(database::locations).orEmpty()

    private fun queueCommandBlocking(trackerId: Long, command: String): GatewayJob {
        if (database.tracker(trackerId)?.registered != true) {
            throw GatewayException("Register this tracker with the gateway before sending commands.")
        }
        val job = gateway.queueCommand(trackerId, command)
        database.addJob(job)
        if (command == "FETCH") database.markFetchRequested(trackerId, System.currentTimeMillis())
        refreshLocalState()
        return job
    }

    private fun queueStaleActiveTrackers(remoteTrackers: List<Tracker>) {
        val now = System.currentTimeMillis()
        val thresholdMs = TimeUnit.MINUTES.toMillis(preferences.syncThresholdMinutes.toLong())
        val pendingFetches = database.pendingJobs().filter { it.command == "FETCH" }.map { it.trackerId }.toSet()
        val failures = mutableListOf<String>()
        for (tracker in remoteTrackers) {
            if (!tracker.isActive || tracker.trackerId in pendingFetches) continue
            val cached = database.tracker(tracker.trackerId)
            val lastAttempt = maxOf(
                cached?.lastSyncAtMs ?: 0L,
                cached?.lastFetchRequestedAtMs ?: 0L
            )
            if (now - lastAttempt < thresholdMs) continue
            try {
                queueCommandBlocking(tracker.trackerId, "FETCH")
            } catch (exception: IOException) {
                failures.add("${tracker.trackerId.toString(16).uppercase()}: ${exception.message}")
            }
        }
        if (failures.isNotEmpty()) showMessage("Automatic FETCH failed for ${failures.joinToString("; ")}")
    }

    private fun refreshTrackersFromGateway(): List<Tracker> {
        val fetched = gateway.trackers()
        val merged = fetched.map { remote ->
            val local = database.tracker(remote.trackerId)
            val battery = gateway.battery(remote.trackerId)
            remote.copy(
                batteryMillivolts = battery.millivolts ?: remote.batteryMillivolts ?: local?.batteryMillivolts,
                lowBatteryLockout = battery.lowBatteryLockout,
                lastSyncAtMs = local?.lastSyncAtMs,
                lastFetchRequestedAtMs = local?.lastFetchRequestedAtMs,
                chargeNotificationSent = local?.chargeNotificationSent ?: false
            )
        }
        database.upsertTrackers(merged)
        return merged
    }

    private fun startConfigSync(singleTrackerId: Long?) {
        val targetIds = singleTrackerId?.let(::listOf)
        post {
            syncingTrackerIds = syncingTrackerIds + targetIds.orEmpty()
            if (targetIds == null) isSyncingAll = true
            message = if (singleTrackerId == null) "Synchronizing active trackers…" else "Synchronizing tracker configuration…"
        }
        ioExecutor.execute {
            val synced = mutableListOf<Long>()
            val errors = mutableListOf<String>()
            try {
                val activeIds = refreshTrackersFromGateway()
                    .filter { it.isActive && (targetIds == null || it.trackerId in targetIds) }
                    .map { it.trackerId }
                if (targetIds != null && activeIds.isEmpty()) {
                    throw GatewayException("Configuration sync is allowed only while the selected tracker is active.")
                }
                if (activeIds.isEmpty()) {
                    throw GatewayException("There are no active trackers to synchronize.")
                }
                for (trackerId in activeIds) {
                    try {
                        syncTrackerBlocking(trackerId)
                        synced.add(trackerId)
                    } catch (exception: IOException) {
                        errors.add("${trackerId.toString(16).uppercase()}: ${exception.message}")
                    }
                }
                val summary = buildString {
                    append("Configuration synced for ${synced.size} active tracker(s).")
                    if (errors.isNotEmpty()) append(" Failed: ${errors.joinToString("; ")}")
                }
                showMessage(summary)
            } catch (exception: IOException) {
                showMessage(exception.message ?: "Could not synchronize tracker configuration.")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                showMessage("Configuration sync was interrupted.")
            } finally {
                post {
                    syncingTrackerIds = syncingTrackerIds - targetIds.orEmpty() - synced.toSet()
                    if (targetIds == null) {
                        syncingTrackerIds = emptySet()
                        isSyncingAll = false
                    }
                }
                refreshLocalState()
            }
        }
    }

    private fun syncTrackerBlocking(trackerId: Long) {
        val localValues = preferences.configuration(trackerId)
        if (!TrackerSettings.validConfiguration(localValues)) {
            throw GatewayException("Local configuration for tracker ${trackerId.toString(16).uppercase()} is invalid.")
        }

        val readJob = queueCommandBlocking(trackerId, "GET_CONFIG")
        val readResult = awaitJob(readJob)
        if (readResult.status != "COMPLETED") {
            throw GatewayException(readResult.detail ?: "GET_CONFIG did not complete for the active tracker.")
        }
        val reportedValues = gateway.configSnapshot(trackerId)
        for (setting in TrackerSettings.all) {
            val desired = localValues.getValue(setting.id)
            if (reportedValues[setting.id] == desired) continue
            val tracker = database.tracker(trackerId)
            if (tracker?.isActive != true) {
                throw GatewayException("Tracker is no longer active; remaining settings were not sent.")
            }
            val job = gateway.queueCommand(
                trackerId,
                "SET_CONFIG",
                setting.id,
                desired
            )
            database.addJob(job)
            refreshLocalState()
            val result = awaitJob(job)
            if (result.status != "COMPLETED") {
                throw GatewayException(
                    result.detail ?: "Setting ${setting.title} was rejected by the tracker."
                )
            }
        }
    }

    private fun awaitJob(submittedJob: GatewayJob): GatewayJob {
        val deadline = System.currentTimeMillis() + MAX_CONFIG_JOB_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val latest = database.job(submittedJob.jobId)
                ?: throw GatewayException("Tracker job disappeared from local storage.")
            if (latest.isTerminal) return latest
            if (Thread.currentThread().isInterrupted) throw IOException("Configuration sync was interrupted.")
            val updated = gateway.job(latest)
            database.updateJob(updated)
            if (updated.isTerminal) return updated
            Thread.sleep(CONFIG_JOB_WAIT_INTERVAL_MS)
        }
        throw GatewayException("Tracker job is still pending. Check its status in the activity list.")
    }

    private fun chooseSelectedTracker() {
        val selected = selectedTrackerId
        if (selected != null && trackers.any { it.trackerId == selected }) return
        val next = trackers.firstOrNull()?.trackerId
        selectedTrackerId = next
        preferences.selectedTrackerId = next
    }

    private fun showMessage(value: String) {
        post { message = value }
    }

    private fun reportJobMonitorError(error: String) {
        if (lastJobMonitorError == error) return
        lastJobMonitorError = error
        showMessage(error)
    }

    private fun startEventService() {
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, GatewayEventService::class.java)
            )
        } catch (exception: SecurityException) {
            showMessage("Android blocked background monitoring. Review notification and battery settings.")
        } catch (exception: IllegalStateException) {
            showMessage("Android could not start background monitoring while the app was not visible.")
        }
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    companion object {
        @Volatile
        var appInForeground: Boolean = false
            private set

        private const val TAG = "CatNavAppState"
        private const val JOB_POLL_INTERVAL_SECONDS = 5L
        private const val CONFIG_JOB_WAIT_INTERVAL_MS = 2_000L
        private val MAX_CONFIG_JOB_WAIT_MS = TimeUnit.HOURS.toMillis(8)
    }

    fun setAppForeground(foreground: Boolean) {
        appInForeground = foreground
    }
}
