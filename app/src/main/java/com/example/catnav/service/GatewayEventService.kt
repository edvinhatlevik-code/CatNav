package com.example.catnav.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.catnav.data.AppPreferences
import com.example.catnav.data.CatNavDatabase
import com.example.catnav.data.GatewayClient
import com.example.catnav.data.GatewayJobProcessor
import com.example.catnav.data.Tracker
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Future
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GatewayEventService : Service() {
    private val streamExecutor = Executors.newSingleThreadExecutor()
    private lateinit var jobExecutor: ScheduledExecutorService
    private lateinit var preferences: AppPreferences
    private lateinit var database: CatNavDatabase
    private lateinit var gateway: GatewayClient
    private lateinit var jobProcessor: GatewayJobProcessor
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    private var streamFuture: Future<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastAlertReconcileAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        database = CatNavDatabase(this)
        gateway = GatewayClient(preferences)
        jobProcessor = GatewayJobProcessor(this)
        jobExecutor = Executors.newSingleThreadScheduledExecutor()
        CatNavNotifications.createChannels(this)
        jobExecutor.scheduleWithFixedDelay(
            { scheduledMaintenance() },
            JOB_POLL_INTERVAL_SECONDS,
            JOB_POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!preferences.eventsEnabled || preferences.apiToken.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(
            CatNavNotifications.SERVICE_NOTIFICATION_ID,
            CatNavNotifications.monitor(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        acquireWakeLock()
        stopEventStream()
        streamFuture = streamExecutor.submit { streamEvents() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopEventStream()
        jobExecutor.shutdownNow()
        streamExecutor.shutdownNow()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun streamEvents() {
        var retryDelaySeconds = INITIAL_RETRY_SECONDS
        while (!Thread.currentThread().isInterrupted) {
            if (!preferences.eventsEnabled || preferences.apiToken.isBlank()) {
                sleepBeforeRetry(retryDelaySeconds)
                continue
            }

            try {
                reconcileAlerts()
                lastAlertReconcileAtMs = System.currentTimeMillis()
                val connection = gateway.openEventsConnection()
                activeConnection.set(connection)
                retryDelaySeconds = INITIAL_RETRY_SECONDS
                readEvents(connection)
            } catch (exception: IOException) {
                Log.w(TAG, "Gateway event stream unavailable: ${exception.message}")
            } catch (exception: SecurityException) {
                Log.e(TAG, "Gateway event stream permission was denied.", exception)
            } finally {
                activeConnection.getAndSet(null)?.disconnect()
            }

            if (!sleepBeforeRetry(retryDelaySeconds)) break
            retryDelaySeconds = (retryDelaySeconds * 2).coerceAtMost(MAX_RETRY_SECONDS)
        }
    }

    private fun readEvents(connection: HttpURLConnection) {
        var eventName = "message"
        val data = StringBuilder()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (!Thread.currentThread().isInterrupted) {
                val line = reader.readLine() ?: return
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            handleEvent(eventName, data.toString().trimEnd('\n'))
                            data.setLength(0)
                        }
                        eventName = "message"
                    }
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                    line.startsWith("data:") -> data.append(line.substringAfter(':').trimStart()).append('\n')
                }
            }
        }
    }

    private fun handleEvent(eventName: String, data: String) {
        if (eventName != "charge_request") return
        val event = try {
            JSONObject(data)
        } catch (exception: org.json.JSONException) {
            throw IOException("Gateway sent malformed charge event data.", exception)
        }
        val trackerId = event.longOrNull("trackerId")
            ?: event.longOrNull("tracker_id")
            ?: throw IOException("Gateway sent a charge alert without a tracker ID.")
        if (trackerId !in 1L..0xFFFF_FFFFL) throw IOException("Gateway sent an invalid tracker ID.")
        val previous = database.tracker(trackerId) ?: Tracker(trackerId = trackerId)
        val millivolts = event.intOrNull("batteryMillivolts")
            ?: event.intOrNull("millivolts")
        val critical = event.intOrNull("criticalBatteryMillivolts")
            ?: event.intOrNull("criticalMillivolts")
        database.setBatteryState(trackerId, millivolts ?: previous.batteryMillivolts, true)
        notifyChargeIfNeeded(trackerId, millivolts, critical)
    }

    private fun reconcileAlerts() {
        val alerts = gateway.alerts()
        val activeTrackerIds = alerts.map { alert ->
            val id = alert.longOrNull("trackerId")
                ?: alert.longOrNull("tracker_id")
                ?: throw IOException("Gateway returned a low-battery alert without a tracker ID.")
            if (id !in 1L..0xFFFF_FFFFL) throw IOException("Gateway returned an invalid tracker ID.")
            id
        }.toSet()
        database.trackers()
            .filter { it.lowBatteryLockout && it.trackerId !in activeTrackerIds }
            .forEach { database.setBatteryState(it.trackerId, it.batteryMillivolts, false) }

        for (alert in alerts) {
            val trackerId = alert.longOrNull("trackerId")
                ?: alert.longOrNull("tracker_id")
                ?: throw IOException("Gateway returned a low-battery alert without a tracker ID.")
            val previous = database.tracker(trackerId) ?: Tracker(trackerId = trackerId)
            val millivolts = alert.intOrNull("batteryMillivolts")
                ?: alert.intOrNull("millivolts")
            val critical = alert.intOrNull("criticalBatteryMillivolts")
                ?: alert.intOrNull("criticalMillivolts")
            database.setBatteryState(trackerId, millivolts ?: previous.batteryMillivolts, true)
            notifyChargeIfNeeded(trackerId, millivolts, critical)
        }
    }

    @Synchronized
    private fun notifyChargeIfNeeded(trackerId: Long, millivolts: Int?, criticalMillivolts: Int?) {
        val tracker = database.tracker(trackerId) ?: return
        if (tracker.chargeNotificationSent) return
        if (CatNavNotifications.chargeRequest(
                this,
                trackerId,
                tracker.displayName,
                millivolts,
                criticalMillivolts
            )
        ) {
            database.markChargeNotificationSent(trackerId)
        } else {
            Log.w(TAG, "Battery notification permission is not enabled.")
        }
    }

    private fun scheduledMaintenance() {
        maintainWakeLock()
        if (!preferences.eventsEnabled || preferences.apiToken.isBlank()) return
        if (System.currentTimeMillis() - lastAlertReconcileAtMs >= ALERT_RECONCILE_INTERVAL_MS) {
            lastAlertReconcileAtMs = System.currentTimeMillis()
            try {
                reconcileAlerts()
            } catch (exception: IOException) {
                Log.w(TAG, "Could not refresh tracker alerts: ${exception.message}")
            }
        }
        if (com.example.catnav.CatNavAppState.appInForeground) return
        try {
            jobProcessor.pollPendingJobs()
        } catch (exception: IOException) {
            Log.w(TAG, "Could not update asynchronous tracker jobs: ${exception.message}")
        } catch (exception: SecurityException) {
            Log.e(TAG, "Tracker job monitoring permission was denied.", exception)
        } catch (exception: IllegalStateException) {
            Log.e(TAG, "Tracker job data could not be saved.", exception)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:Events").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun stopEventStream() {
        activeConnection.getAndSet(null)?.disconnect()
        streamFuture?.cancel(true)
        streamFuture = null
    }

    private fun sleepBeforeRetry(seconds: Long): Boolean =
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds))
            !Thread.currentThread().isInterrupted
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun JSONObject.longOrNull(key: String): Long? {
        val value = opt(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun JSONObject.intOrNull(key: String): Int? =
        longOrNull(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    companion object {
        private const val TAG = "CatNavEvents"
        private const val INITIAL_RETRY_SECONDS = 5L
        private const val MAX_RETRY_SECONDS = 60L
        private const val JOB_POLL_INTERVAL_SECONDS = 5L
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1_000L
        private const val ALERT_RECONCILE_INTERVAL_MS = 60L * 1_000L
    }

    private fun maintainWakeLock() {
        if (!preferences.eventsEnabled || preferences.apiToken.isBlank()) return
        acquireWakeLock()
    }
}
