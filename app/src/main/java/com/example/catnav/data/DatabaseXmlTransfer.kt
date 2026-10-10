package com.example.catnav.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

data class ImportResult(val trackers: Int, val locations: Int)

object DatabaseXmlTransfer {
    fun export(database: CatNavDatabase, output: OutputStream) {
        val serializer: XmlSerializer = Xml.newSerializer()
        serializer.setOutput(OutputStreamWriter(output, Charsets.UTF_8))
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "catnav")
        serializer.attribute(null, "version", "1")

        database.trackers().forEach { tracker ->
            serializer.startTag(null, "tracker")
            serializer.attribute(null, "id", tracker.trackerId.toString())
            tracker.catName?.let { serializer.attribute(null, "catName", it) }
            serializer.attribute(null, "state", tracker.state)
            tracker.batteryMillivolts?.let {
                serializer.attribute(null, "batteryMillivolts", it.toString())
            }
            serializer.attribute(null, "lowBatteryLockout", tracker.lowBatteryLockout.toString())
            database.locations(tracker.trackerId).forEach { record ->
                serializer.startTag(null, "location")
                serializer.attribute(null, "recordSequence", record.recordSequence.toString())
                serializer.attribute(null, "utcSeconds", record.utcSeconds.toString())
                serializer.attribute(null, "latitudeE7", record.latitudeE7.toString())
                serializer.attribute(null, "longitudeE7", record.longitudeE7.toString())
                serializer.attribute(null, "receivedAtMs", record.receivedAtMs.toString())
                serializer.endTag(null, "location")
            }
            serializer.endTag(null, "tracker")
        }

        serializer.endTag(null, "catnav")
        serializer.endDocument()
        serializer.flush()
    }

    fun import(database: CatNavDatabase, input: InputStream): ImportResult {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(input, Charsets.UTF_8.name())
        }
        val records = mutableListOf<LocationRecord>()
        val importedTrackers = mutableMapOf<Long, Tracker>()
        var currentTrackerId: Long? = null
        var sawRoot = false
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "catnav" -> {
                        require(parser.depth == 1) { "CatNav XML export root must be the document element." }
                        if (sawRoot) throw IllegalArgumentException("XML contains multiple CatNav roots.")
                        require(parser.getAttributeValue(null, "version") == "1") {
                            "This CatNav XML export version is not supported."
                        }
                        sawRoot = true
                    }
                    "tracker" -> {
                        require(sawRoot) { "XML tracker appears outside the CatNav root." }
                        val trackerId = parser.requiredLongAttribute("id")
                        require(trackerId in 1L..0xFFFF_FFFFL) { "XML contains an invalid tracker ID." }
                        currentTrackerId = trackerId
                        importedTrackers[trackerId] = Tracker(
                            trackerId = trackerId,
                            catName = parser.getAttributeValue(null, "catName"),
                            state = "UNKNOWN",
                            registered = false
                        )
                    }
                    "location" -> {
                        require(sawRoot) { "XML location appears outside the CatNav root." }
                        val trackerId = currentTrackerId
                            ?: throw IllegalArgumentException("Location appears outside a tracker element.")
                        val record = LocationRecord(
                            trackerId = trackerId,
                            recordSequence = parser.requiredLongAttribute("recordSequence"),
                            utcSeconds = parser.requiredLongAttribute("utcSeconds"),
                            latitudeE7 = parser.requiredIntAttribute("latitudeE7"),
                            longitudeE7 = parser.requiredIntAttribute("longitudeE7"),
                            receivedAtMs = parser.getAttributeValue(null, "receivedAtMs")?.toLongOrNull()
                                ?: System.currentTimeMillis()
                        )
                        require(record.recordSequence in 0L..0xFFFF_FFFFL) { "XML contains an invalid record sequence." }
                        require(record.utcSeconds in 0L..0xFFFF_FFFFL) { "XML contains an invalid timestamp." }
                        require(record.latitudeE7 in -900_000_000..900_000_000) { "XML contains an invalid latitude." }
                        require(record.longitudeE7 in -1_800_000_000..1_800_000_000) { "XML contains an invalid longitude." }
                        records.add(record)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "tracker") currentTrackerId = null
            }
            eventType = parser.next()
        }

        require(sawRoot) { "The selected file is not a CatNav XML export." }
        importedTrackers.values
            .filter { database.tracker(it.trackerId) == null }
            .forEach(database::upsertTracker)
        val insertedLocations = database.insertLocations(records)
        return ImportResult(importedTrackers.size, insertedLocations)
    }

    private fun XmlPullParser.requiredLongAttribute(name: String): Long =
        getAttributeValue(null, name)?.toLongOrNull()
            ?: throw IllegalArgumentException("XML is missing a valid '$name' attribute.")

    private fun XmlPullParser.requiredIntAttribute(name: String): Int =
        getAttributeValue(null, name)?.toIntOrNull()
            ?: throw IllegalArgumentException("XML is missing a valid '$name' attribute.")
}
