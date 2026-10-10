package com.example.catnav.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import androidx.core.database.sqlite.transaction

class CatNavDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trackers (
                tracker_id INTEGER PRIMARY KEY,
                cat_name TEXT,
                state TEXT NOT NULL DEFAULT 'UNKNOWN',
                battery_mv INTEGER,
                low_battery_lockout INTEGER NOT NULL DEFAULT 0,
                charge_notified INTEGER NOT NULL DEFAULT 0,
                rssi INTEGER,
                snr REAL,
                last_seen_ms INTEGER,
                last_sync_ms INTEGER,
                last_fetch_requested_ms INTEGER,
                registered INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE locations (
                tracker_id INTEGER NOT NULL,
                record_sequence INTEGER NOT NULL,
                utc_seconds INTEGER NOT NULL,
                latitude_e7 INTEGER NOT NULL,
                longitude_e7 INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL,
                PRIMARY KEY (tracker_id, record_sequence),
                FOREIGN KEY (tracker_id) REFERENCES trackers(tracker_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE jobs (
                job_id TEXT PRIMARY KEY,
                tracker_id INTEGER NOT NULL,
                command TEXT NOT NULL,
                status TEXT NOT NULL,
                detail TEXT,
                partial INTEGER NOT NULL DEFAULT 0,
                pending_records INTEGER,
                acknowledged_chunks INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                handled INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX locations_by_time ON locations(tracker_id, utc_seconds, received_at_ms)")
        db.execSQL("CREATE INDEX jobs_by_status ON jobs(status, created_at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE trackers ADD COLUMN cat_name TEXT")
    }

    fun upsertTracker(tracker: Tracker) {
        val values = ContentValues().apply {
            put("tracker_id", tracker.trackerId)
            if (tracker.catName == null) putNull("cat_name") else put("cat_name", tracker.catName)
            put("state", tracker.state)
            putNullable("battery_mv", tracker.batteryMillivolts)
            put("low_battery_lockout", if (tracker.lowBatteryLockout) 1 else 0)
            put(
                "charge_notified",
                if (tracker.lowBatteryLockout && tracker.chargeNotificationSent) 1 else 0
            )
            putNullable("rssi", tracker.rssi)
            putNullable("snr", tracker.snr)
            putNullable("last_seen_ms", tracker.lastSeenAtMs)
            putNullable("last_sync_ms", tracker.lastSyncAtMs)
            putNullable("last_fetch_requested_ms", tracker.lastFetchRequestedAtMs)
            put("registered", if (tracker.registered) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("trackers", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertTrackers(trackers: List<Tracker>) {
        writableDatabase.transaction {
            trackers.forEach(::upsertTracker)
        }
    }

    fun tracker(trackerId: Long): Tracker? =
        readableDatabase.query(
            "trackers",
            null,
            "tracker_id = ?",
            arrayOf(trackerId.toString()),
            null,
            null,
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTracker() else null }

    fun trackers(): List<Tracker> =
        readableDatabase.query("trackers", null, null, null, null, null, "tracker_id ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toTracker())
            }
        }

    fun setTrackerState(trackerId: Long, state: String) {
        val tracker = tracker(trackerId) ?: Tracker(trackerId = trackerId)
        upsertTracker(tracker.copy(state = state))
    }

    fun setBatteryState(trackerId: Long, millivolts: Int?, lockout: Boolean) {
        val tracker = tracker(trackerId) ?: Tracker(trackerId = trackerId)
        upsertTracker(
            tracker.copy(
                batteryMillivolts = millivolts,
                lowBatteryLockout = lockout,
                chargeNotificationSent = lockout && tracker.chargeNotificationSent
            )
        )
    }

    fun markChargeNotificationSent(trackerId: Long) {
        val tracker = tracker(trackerId) ?: return
        if (tracker.lowBatteryLockout) upsertTracker(tracker.copy(chargeNotificationSent = true))
    }

    fun markFetchRequested(trackerId: Long, requestedAtMs: Long) {
        val tracker = tracker(trackerId) ?: Tracker(trackerId = trackerId)
        upsertTracker(tracker.copy(lastFetchRequestedAtMs = requestedAtMs))
    }

    fun markFetchCompleted(trackerId: Long, completedAtMs: Long) {
        val tracker = tracker(trackerId) ?: Tracker(trackerId = trackerId)
        upsertTracker(tracker.copy(lastSyncAtMs = completedAtMs, lastFetchRequestedAtMs = null))
    }

    fun clearFetchRequest(trackerId: Long) {
        val tracker = tracker(trackerId) ?: return
        upsertTracker(tracker.copy(lastFetchRequestedAtMs = null))
    }

    fun locations(trackerId: Long): List<LocationRecord> =
        readableDatabase.query(
            "locations",
            null,
            "tracker_id = ?",
            arrayOf(trackerId.toString()),
            null,
            null,
            "CASE WHEN utc_seconds > 0 THEN utc_seconds ELSE received_at_ms / 1000 END ASC, record_sequence ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LocationRecord(
                            trackerId = cursor.getLong(cursor.getColumnIndexOrThrow("tracker_id")),
                            recordSequence = cursor.getLong(cursor.getColumnIndexOrThrow("record_sequence")),
                            utcSeconds = cursor.getLong(cursor.getColumnIndexOrThrow("utc_seconds")),
                            latitudeE7 = cursor.getInt(cursor.getColumnIndexOrThrow("latitude_e7")),
                            longitudeE7 = cursor.getInt(cursor.getColumnIndexOrThrow("longitude_e7")),
                            receivedAtMs = cursor.getLong(cursor.getColumnIndexOrThrow("received_at_ms"))
                        )
                    )
                }
            }
        }

    fun locationCount(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM locations", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    fun insertLocations(records: List<LocationRecord>): Int {
        if (records.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.transaction {
            for (record in records) {
                require(record.trackerId in 1L..0xFFFF_FFFFL) { "Tracker ID is outside the 32-bit range." }
                require(record.recordSequence in 0L..0xFFFF_FFFFL) { "Record sequence is outside the 32-bit range." }
                require(record.utcSeconds in 0L..0xFFFF_FFFFL) { "UTC timestamp is outside the protocol range." }
                require(record.latitudeE7 in -900_000_000..900_000_000) { "Latitude is outside its valid range." }
                require(record.longitudeE7 in -1_800_000_000..1_800_000_000) { "Longitude is outside its valid range." }

                val existing = db.query(
                    "locations",
                    arrayOf("utc_seconds", "latitude_e7", "longitude_e7"),
                    "tracker_id = ? AND record_sequence = ?",
                    arrayOf(record.trackerId.toString(), record.recordSequence.toString()),
                    null,
                    null,
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        listOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                    } else {
                        null
                    }
                }
                if (existing != null) {
                    check(
                        existing[0] == record.utcSeconds &&
                            existing[1] == record.latitudeE7.toLong() &&
                            existing[2] == record.longitudeE7.toLong()
                    ) { "Conflicting location data for tracker ${record.trackerId}, record ${record.recordSequence}." }
                    continue
                }

                val values = ContentValues().apply {
                    put("tracker_id", record.trackerId)
                    put("record_sequence", record.recordSequence)
                    put("utc_seconds", record.utcSeconds)
                    put("latitude_e7", record.latitudeE7)
                    put("longitude_e7", record.longitudeE7)
                    put("received_at_ms", record.receivedAtMs)
                }
                db.insertOrThrow("locations", null, values)
                inserted++
            }
        }
        return inserted
    }

    fun addJob(job: GatewayJob) {
        val now = System.currentTimeMillis()
        val values = job.toContentValues(now)
        writableDatabase.insertWithOnConflict("jobs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateJob(job: GatewayJob): String? {
        val previousStatus = readableDatabase.query(
            "jobs",
            arrayOf("status"),
            "job_id = ?",
            arrayOf(job.jobId),
            null,
            null,
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val values = job.toContentValues(System.currentTimeMillis()).apply { remove("handled") }
        writableDatabase.update("jobs", values, "job_id = ?", arrayOf(job.jobId))
        return previousStatus
    }

    fun job(jobId: String): GatewayJob? =
        readableDatabase.query(
            "jobs",
            null,
            "job_id = ?",
            arrayOf(jobId),
            null,
            null,
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toJob() else null }

    fun pendingJobs(): List<GatewayJob> =
        readableDatabase.query(
            "jobs",
            null,
            "status NOT IN ('COMPLETED', 'FAILED', 'TIMED_OUT') OR handled = 0",
            null,
            null,
            null,
            "created_at_ms ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toJob())
            }
        }

    fun markJobHandled(jobId: String) {
        writableDatabase.execSQL("UPDATE jobs SET handled = 1 WHERE job_id = ?", arrayOf(jobId))
    }

    fun recentJobs(limit: Int = 20): List<GatewayJob> =
        readableDatabase.query(
            "jobs",
            null,
            null,
            null,
            null,
            null,
            "created_at_ms DESC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toJob())
            }
        }

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Double?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun GatewayJob.toContentValues(updatedAtMs: Long): ContentValues =
        ContentValues().apply {
            put("job_id", jobId)
            put("tracker_id", trackerId)
            put("command", command)
            put("status", status)
            if (detail == null) putNull("detail") else put("detail", detail)
            put("partial", if (partial) 1 else 0)
            if (pendingRecords == null) putNull("pending_records") else put("pending_records", pendingRecords)
            if (acknowledgedChunks == null) {
                putNull("acknowledged_chunks")
            } else {
                put("acknowledged_chunks", acknowledgedChunks)
            }
            put("created_at_ms", createdAtMs)
            put("updated_at_ms", updatedAtMs)
            put("handled", if (handled) 1 else 0)
        }

    private fun android.database.Cursor.toTracker(): Tracker = Tracker(
        trackerId = getLong(getColumnIndexOrThrow("tracker_id")),
        catName = getString(getColumnIndexOrThrow("cat_name")),
        state = getString(getColumnIndexOrThrow("state")),
        batteryMillivolts = nullableInt("battery_mv"),
        lowBatteryLockout = getInt(getColumnIndexOrThrow("low_battery_lockout")) != 0,
        chargeNotificationSent = getInt(getColumnIndexOrThrow("charge_notified")) != 0,
        rssi = nullableInt("rssi"),
        snr = nullableDouble("snr"),
        lastSeenAtMs = nullableLong("last_seen_ms"),
        lastSyncAtMs = nullableLong("last_sync_ms"),
        lastFetchRequestedAtMs = nullableLong("last_fetch_requested_ms"),
        registered = getInt(getColumnIndexOrThrow("registered")) != 0
    )

    private fun android.database.Cursor.toJob(): GatewayJob = GatewayJob(
        jobId = getString(getColumnIndexOrThrow("job_id")),
        trackerId = getLong(getColumnIndexOrThrow("tracker_id")),
        command = getString(getColumnIndexOrThrow("command")),
        status = getString(getColumnIndexOrThrow("status")),
        detail = getString(getColumnIndexOrThrow("detail")),
        partial = getInt(getColumnIndexOrThrow("partial")) != 0,
        pendingRecords = nullableLong("pending_records"),
        acknowledgedChunks = nullableLong("acknowledged_chunks"),
        createdAtMs = getLong(getColumnIndexOrThrow("created_at_ms")),
        handled = getInt(getColumnIndexOrThrow("handled")) != 0
    )

    private fun android.database.Cursor.nullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun android.database.Cursor.nullableDouble(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun android.database.Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    companion object {
        private const val DATABASE_NAME = "catnav.db"
        private const val DATABASE_VERSION = 2
    }
}
