package com.example.catnav.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.catnav.CatNavAppState
import com.example.catnav.data.LocationRecord
import java.io.File
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private enum class MapMode(val title: String) {
    TRACE("Trace"),
    HEAT("Heat map")
}

private enum class TimeRange(val title: String, val durationMs: Long?) {
    DAY("24 hours", 24L * 60 * 60 * 1_000),
    WEEK("7 days", 7L * 24 * 60 * 60 * 1_000),
    MONTH("30 days", 30L * 24 * 60 * 60 * 1_000),
    ALL("All time", null)
}

@Composable
internal fun MapScreen(state: CatNavAppState) {
    var selectedMode by rememberSaveable { mutableStateOf(MapMode.TRACE.name) }
    var selectedRange by rememberSaveable { mutableStateOf(TimeRange.WEEK.name) }
    var selectedPoint by remember { mutableStateOf<LocationRecord?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val selectedTracker = state.trackers.firstOrNull { it.trackerId == state.selectedTrackerId }
        ?: state.trackers.firstOrNull()
    val criticalMillivolts = selectedTracker
        ?.let { state.localConfiguration(it.trackerId)[6]?.toInt() }
        ?: 3_300
    val allRecords = state.locationsFor(selectedTracker?.trackerId)
    val range = TimeRange.valueOf(selectedRange)
    val filteredRecords = remember(allRecords, range) {
        val boundary = range.durationMs?.let { System.currentTimeMillis() - it }
        if (boundary == null) allRecords else allRecords.filter { it.timestampMs >= boundary }
    }
    val mode = MapMode.valueOf(selectedMode)

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Explore", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Movement history, stored on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${filteredRecords.size} points",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (state.trackers.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.trackers.forEach { tracker ->
                        FilterChip(
                            selected = tracker.trackerId == selectedTracker?.trackerId,
                            onClick = {
                                state.selectTracker(tracker.trackerId)
                                selectedPoint = null
                            },
                            label = { Text(formatTrackerId(tracker.trackerId)) }
                        )
                    }
                }
            }
            if (selectedTracker != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackerStatePill(selectedTracker)
                        Spacer(Modifier.width(10.dp))
                        BatteryMeter(
                            selectedTracker.batteryMillivolts,
                            criticalMillivolts,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                MapMode.entries.forEach { option ->
                    FilterChip(
                        selected = selectedMode == option.name,
                        onClick = {
                            selectedMode = option.name
                            selectedPoint = null
                        },
                        label = { Text(option.title) }
                    )
                }
                Spacer(Modifier.width(6.dp))
                TimeRange.entries.forEach { option ->
                    FilterChip(
                        selected = selectedRange == option.name,
                        onClick = {
                            selectedRange = option.name
                            selectedPoint = null
                        },
                        label = { Text(option.title) }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    createMapView(context).also { mapView = it }
                },
                update = { map ->
                    map.overlays.clear()
                    map.overlays.add(
                        LocationMapOverlay(filteredRecords, mode == MapMode.HEAT) {
                            selectedPoint = it
                        }
                    )
                    val centerToken = buildString {
                        append(selectedTracker?.trackerId ?: "none")
                        append(':')
                        append(filteredRecords.size)
                        append(':')
                        append(filteredRecords.lastOrNull()?.recordSequence ?: "empty")
                    }
                    if (map.tag != centerToken) {
                        centerOnLocations(map, filteredRecords)
                        map.tag = centerToken
                    }
                    map.invalidate()
                }
            )
            DisposableEffect(mapView) {
                mapView?.onResume()
                onDispose {
                    mapView?.onPause()
                    mapView?.onDetach()
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 2.dp
            ) {
                Text(
                    "© OpenStreetMap contributors",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF36423E)
                )
            }
            val point = selectedPoint
            if (point != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    shape = RoundedCornerShape(15.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 5.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = Color(0xFFE3F2ED)) {
                            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(formatMapTimestamp(point.timestampMs), fontWeight = FontWeight.Bold)
                            Text(
                                "${String.format(Locale.US, "%.5f", point.latitude)}, ${String.format(Locale.US, "%.5f", point.longitude)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (filteredRecords.isEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(20.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No locations in this range", fontWeight = FontWeight.Bold)
                        Text(
                            if (allRecords.isEmpty()) "Use Fetch to bring tracker history onto this phone."
                            else "Try a wider time filter to see older trips.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun createMapView(context: Context): MapView {
    val osmdroidFolder = context.getExternalFilesDir("osmdroid") ?: File(context.filesDir, "osmdroid")
    val tileFolder = File(osmdroidFolder, "tiles")
    osmdroidFolder.mkdirs()
    tileFolder.mkdirs()
    Configuration.getInstance().apply {
        userAgentValue = "CatNav/1.0 (${context.packageName})"
        osmdroidBasePath = osmdroidFolder
        osmdroidTileCache = tileFolder
    }
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        setTilesScaledToDpi(true)
        controller.setZoom(DEFAULT_MAP_ZOOM)
        controller.setCenter(GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE))
    }
}

private fun centerOnLocations(map: MapView, records: List<LocationRecord>) {
    if (records.isEmpty()) return
    val latitudes = records.map { it.latitude }
    val longitudes = records.map { it.longitude }
    val latitudeSpread = latitudes.maxOrNull()!! - latitudes.minOrNull()!!
    val longitudeSpread = longitudes.maxOrNull()!! - longitudes.minOrNull()!!
    if (records.size == 1 || (abs(latitudeSpread) < MIN_COORDINATE_SPREAD && abs(longitudeSpread) < MIN_COORDINATE_SPREAD)) {
        val latest = records.last()
        map.controller.setZoom(DEFAULT_MAP_ZOOM)
        map.controller.setCenter(GeoPoint(latest.latitude, latest.longitude))
        return
    }
    map.zoomToBoundingBox(
        BoundingBox(
            latitudes.maxOrNull()!!,
            longitudes.maxOrNull()!!,
            latitudes.minOrNull()!!,
            longitudes.minOrNull()!!
        ),
        true,
        MAP_FIT_PADDING_PX
    )
}

private fun formatMapTimestamp(timestampMs: Long): String =
    DateTimeFormatter.ofPattern("EEEE, d MMM · HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMs))

private const val DEFAULT_MAP_ZOOM = 15.0
private const val DEFAULT_LATITUDE = 59.9139
private const val DEFAULT_LONGITUDE = 10.7522
private const val MIN_COORDINATE_SPREAD = 0.0002
private const val MAP_FIT_PADDING_PX = 90
