package com.example.catnav.data

import android.content.Context
import android.util.Log
import com.example.catnav.service.CatNavNotifications

class GatewayJobProcessor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val database = CatNavDatabase(appContext)
    private val gateway = GatewayClient(preferences)

    fun pollPendingJobs() {
        for (storedJob in database.pendingJobs()) {
            val currentJob = if (storedJob.isTerminal) {
                storedJob
            } else {
                gateway.job(storedJob).also(database::updateJob)
            }
            if (!currentJob.isTerminal || currentJob.handled) continue

            if (handleTerminalJob(currentJob)) database.markJobHandled(currentJob.jobId)
        }
    }

    private fun handleTerminalJob(job: GatewayJob): Boolean {
        if (job.status != "COMPLETED") {
            if (job.command == "FETCH") database.clearFetchRequest(job.trackerId)
            return true
        }

        return when (job.command) {
            "WAKE" -> {
                database.setTrackerState(job.trackerId, "ACTIVE")
                val trackerName = database.tracker(job.trackerId)?.displayName
                    ?: Tracker(job.trackerId).displayName
                val delivered = CatNavNotifications.trackerAwake(appContext, job.trackerId, trackerName)
                if (!delivered) Log.w(TAG, "Tracker wake notification permission is not enabled.")
                delivered
            }
            "SLEEP" -> {
                database.setTrackerState(job.trackerId, "DORMANT")
                true
            }
            "FETCH" -> {
                if (database.tracker(job.trackerId) == null) {
                    database.upsertTracker(Tracker(trackerId = job.trackerId))
                }
                database.insertLocations(gateway.fetchLocations(job.trackerId))
                database.markFetchCompleted(job.trackerId, System.currentTimeMillis())
                if (job.partial && (job.pendingRecords ?: 0L) > 0L) {
                    val stillActive = gateway.trackers().any {
                        it.trackerId == job.trackerId && it.isActive
                    }
                    if (stillActive) {
                        val followUp = gateway.queueCommand(job.trackerId, "FETCH")
                        database.addJob(followUp)
                        database.markFetchRequested(job.trackerId, System.currentTimeMillis())
                    }
                }
                true
            }
            else -> true
        }
    }

    companion object {
        private const val TAG = "CatNavJobs"
    }
}
