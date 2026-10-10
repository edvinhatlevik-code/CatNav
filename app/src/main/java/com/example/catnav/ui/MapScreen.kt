package com.example.catnav.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.catnav.CatNavAppState
import com.example.catnav.data.LocationRecord
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
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

private const val POINTS_SOURCE_ID = "catnav-points-source"
private const val ROUTE_SOURCE_ID = "catnav-route-source"
private const val HEATMAP_LAYER_ID = "catnav-heatmap-layer"
private const val ROUTE_LAYER_ID = "catnav-route-layer"
private const val POINTS_LAYER_ID = "catnav-points-layer"
private const val LATEST_HALO_LAYER_ID = "catnav-latest-halo-layer"
private const val LATEST_MARKER_LAYER_ID = "catnav-latest-marker-layer"

private const val ORTHOPHOTO_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "orthophoto-tiles": {
      "type": "raster",
      "tiles": [
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
      ],
      "tileSize": 256,
      "maxzoom": 17,
      "attribution": "© Esri — Source: Esri, Earthstar Geographics"
    }
  },
  "layers": [
    {
      "id": "orthophoto-layer",
      "type": "raster",
      "source": "orthophoto-tiles",
      "minzoom": 0,
      "maxzoom": 22
    }
  ]
}
"""

@Composable
internal fun MapScreen(state: CatNavAppState) {
    var selectedMode by rememberSaveable { mutableStateOf(MapMode.TRACE.name) }
    var selectedRange by rememberSaveable { mutableStateOf(TimeRange.WEEK.name) }
    var selectedPoint by remember { mutableStateOf<LocationRecord?>(null) }
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var mapLibreInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapStyleInstance by remember { mutableStateOf<Style?>(null) }

    val context = LocalContext.current
    val selectedTracker = state.trackers.firstOrNull { it.trackerId == state.selectedTrackerId }
        ?: state.trackers.firstOrNull()
    val criticalMillivolts = selectedTracker
        ?.let { state.localConfiguration(it.trackerId)[5]?.toInt() }
        ?: 3_300
    val allRecords = state.locationsFor(selectedTracker?.trackerId)
    val range = TimeRange.valueOf(selectedRange)
    val filteredRecords = remember(allRecords, range) {
        val boundary = range.durationMs?.let { System.currentTimeMillis() - it }
        if (boundary == null) allRecords else allRecords.filter { it.timestampMs >= boundary }
    }
    val mode = MapMode.valueOf(selectedMode)

    val lifecycleOwner = LocalLifecycleOwner.current

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
                            label = { Text(tracker.displayName) }
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
                .clipToBounds()
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapLibre.getInstance(ctx)
                    MapView(ctx).apply {
                        onCreate(null)
                        getMapAsync { map ->
                            mapLibreInstance = map
                            map.setStyle(Style.Builder().fromJson(ORTHOPHOTO_STYLE_JSON)) { style ->
                                mapStyleInstance = style
                                setupMapLayers(style, filteredRecords, mode)
                                map.addOnMapClickListener { latLng ->
                                    val screenPoint = map.projection.toScreenLocation(latLng)
                                    val closest = filteredRecords.minByOrNull { record ->
                                        val recScreen = map.projection.toScreenLocation(LatLng(record.latitude, record.longitude))
                                        val dx = screenPoint.x - recScreen.x
                                        val dy = screenPoint.y - recScreen.y
                                        dx * dx + dy * dy
                                    }
                                    if (closest != null) {
                                        val recScreen = map.projection.toScreenLocation(LatLng(closest.latitude, closest.longitude))
                                        val dx = screenPoint.x - recScreen.x
                                        val dy = screenPoint.y - recScreen.y
                                        if (dx * dx + dy * dy <= 60f * 60f) {
                                            selectedPoint = closest
                                            return@addOnMapClickListener true
                                        }
                                    }
                                    selectedPoint = null
                                    false
                                }
                                centerOnLocations(map, filteredRecords)
                            }
                        }
                    }.also { mapViewInstance = it }
                }
            )

            DisposableEffect(mapViewInstance, lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> mapViewInstance?.onStart()
                        Lifecycle.Event.ON_RESUME -> mapViewInstance?.onResume()
                        Lifecycle.Event.ON_PAUSE -> mapViewInstance?.onPause()
                        Lifecycle.Event.ON_STOP -> mapViewInstance?.onStop()
                        Lifecycle.Event.ON_DESTROY -> mapViewInstance?.onDestroy()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            LaunchedEffect(mapStyleInstance, filteredRecords, mode) {
                val style = mapStyleInstance ?: return@LaunchedEffect
                val map = mapLibreInstance ?: return@LaunchedEffect
                updateMapData(style, filteredRecords, mode)
                centerOnLocations(map, filteredRecords)
                ensureOfflineRegionCached(context, filteredRecords)
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.White.copy(alpha = 0.92f),
                shadowElevation = 2.dp
            ) {
                Text(
                    "© Esri — Earthstar Geographics",
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
                                Icon(
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

private fun setupMapLayers(style: Style, records: List<LocationRecord>, mode: MapMode) {
    val pointsSource = GeoJsonSource(POINTS_SOURCE_ID, createPointsFeatureCollection(records))
    val routeSource = GeoJsonSource(ROUTE_SOURCE_ID, createRouteFeatureCollection(records))
    style.addSource(pointsSource)
    style.addSource(routeSource)

    val heatmapLayer = HeatmapLayer(HEATMAP_LAYER_ID, POINTS_SOURCE_ID).apply {
        setProperties(
            PropertyFactory.heatmapColor(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.heatmapDensity(),
                    Expression.literal(0), Expression.rgba(0, 0, 0, 0),
                    Expression.literal(0.2), Expression.rgba(244, 163, 73, 0.4),
                    Expression.literal(0.6), Expression.rgba(244, 163, 73, 0.8),
                    Expression.literal(1.0), Expression.rgba(243, 95, 75, 1.0)
                )
            ),
            PropertyFactory.heatmapRadius(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.literal(3), Expression.literal(12),
                    Expression.literal(12), Expression.literal(25),
                    Expression.literal(17), Expression.literal(45)
                )
            ),
            PropertyFactory.heatmapWeight(1.0f),
            PropertyFactory.heatmapOpacity(0.85f),
            PropertyFactory.visibility(if (mode == MapMode.HEAT) Property.VISIBLE else Property.NONE)
        )
    }
    style.addLayer(heatmapLayer)

    val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).apply {
        setProperties(
            PropertyFactory.lineColor("#087E72"),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.visibility(if (mode == MapMode.TRACE) Property.VISIBLE else Property.NONE)
        )
    }
    style.addLayer(routeLayer)

    val pointsLayer = CircleLayer(POINTS_LAYER_ID, POINTS_SOURCE_ID).apply {
        setProperties(
            PropertyFactory.circleColor("#087E72"),
            PropertyFactory.circleRadius(4f),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.visibility(if (mode == MapMode.TRACE) Property.VISIBLE else Property.NONE)
        )
    }
    style.addLayer(pointsLayer)

    val latestHaloLayer = CircleLayer(LATEST_HALO_LAYER_ID, POINTS_SOURCE_ID).apply {
        setFilter(Expression.eq(Expression.get("isLatest"), true))
        setProperties(
            PropertyFactory.circleColor("#55FF7043"),
            PropertyFactory.circleRadius(18f)
        )
    }
    val latestMarkerLayer = CircleLayer(LATEST_MARKER_LAYER_ID, POINTS_SOURCE_ID).apply {
        setFilter(Expression.eq(Expression.get("isLatest"), true))
        setProperties(
            PropertyFactory.circleColor("#E66B47"),
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#FFFFFF")
        )
    }
    style.addLayer(latestHaloLayer)
    style.addLayer(latestMarkerLayer)
}

private fun updateMapData(style: Style, records: List<LocationRecord>, mode: MapMode) {
    (style.getSource(POINTS_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(createPointsFeatureCollection(records))
    (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(createRouteFeatureCollection(records))

    style.getLayer(HEATMAP_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(if (mode == MapMode.HEAT) Property.VISIBLE else Property.NONE)
    )
    style.getLayer(ROUTE_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(if (mode == MapMode.TRACE) Property.VISIBLE else Property.NONE)
    )
    style.getLayer(POINTS_LAYER_ID)?.setProperties(
        PropertyFactory.visibility(if (mode == MapMode.TRACE) Property.VISIBLE else Property.NONE)
    )
}

private fun createPointsFeatureCollection(records: List<LocationRecord>): FeatureCollection {
    if (records.isEmpty()) return FeatureCollection.fromFeatures(emptyArray())
    val features = records.mapIndexed { index, record ->
        val feature = Feature.fromGeometry(Point.fromLngLat(record.longitude, record.latitude))
        feature.addNumberProperty("timestampMs", record.timestampMs)
        feature.addNumberProperty("index", index)
        feature.addBooleanProperty("isLatest", index == records.lastIndex)
        feature
    }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

private fun createRouteFeatureCollection(records: List<LocationRecord>): FeatureCollection {
    if (records.size < 2) return FeatureCollection.fromFeatures(emptyArray())
    val points = records.map { Point.fromLngLat(it.longitude, it.latitude) }
    val lineString = LineString.fromLngLats(points)
    return FeatureCollection.fromFeatures(arrayOf(Feature.fromGeometry(lineString)))
}

private fun centerOnLocations(map: MapLibreMap, records: List<LocationRecord>) {
    if (records.isEmpty()) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(DEFAULT_LATITUDE, DEFAULT_LONGITUDE), DEFAULT_MAP_ZOOM))
        return
    }
    val latitudes = records.map { it.latitude }
    val longitudes = records.map { it.longitude }
    val latitudeSpread = latitudes.maxOrNull()!! - latitudes.minOrNull()!!
    val longitudeSpread = longitudes.maxOrNull()!! - longitudes.minOrNull()!!
    if (records.size == 1 || (abs(latitudeSpread) < MIN_COORDINATE_SPREAD && abs(longitudeSpread) < MIN_COORDINATE_SPREAD)) {
        val latest = records.last()
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latest.latitude, latest.longitude), DEFAULT_MAP_ZOOM))
        return
    }
    val boundsBuilder = LatLngBounds.Builder()
    records.forEach { boundsBuilder.include(LatLng(it.latitude, it.longitude)) }
    try {
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), MAP_FIT_PADDING_PX), 1000)
    } catch (_: Exception) {
        val latest = records.last()
        map.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latest.latitude, latest.longitude), DEFAULT_MAP_ZOOM))
    }
}

private fun ensureOfflineRegionCached(context: Context, records: List<LocationRecord>) {
    if (records.isEmpty()) return
    val latitudes = records.map { it.latitude }
    val longitudes = records.map { it.longitude }
    val minLat = (latitudes.minOrNull()!! - 0.05).coerceAtLeast(-85.0)
    val maxLat = (latitudes.maxOrNull()!! + 0.05).coerceAtMost(85.0)
    val minLon = (longitudes.minOrNull()!! - 0.08).coerceAtLeast(-180.0)
    val maxLon = (longitudes.maxOrNull()!! + 0.08).coerceAtMost(180.0)

    val bounds = LatLngBounds.Builder()
        .include(LatLng(maxLat, maxLon))
        .include(LatLng(minLat, minLon))
        .build()

    try {
        val offlineManager = OfflineManager.getInstance(context)
        val definition = OfflineTilePyramidRegionDefinition(
            ORTHOPHOTO_STYLE_JSON,
            bounds,
            10.0,
            17.0,
            context.resources.displayMetrics.density
        )

        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val hasExistingRegion = offlineRegions?.any { region ->
                    try {
                        val regDef = region.definition as? OfflineTilePyramidRegionDefinition
                        val b = regDef?.bounds
                        b != null && abs(b.latitudeNorth - bounds.latitudeNorth) < 0.02 &&
                                abs(b.longitudeEast - bounds.longitudeEast) < 0.02
                    } catch (_: Exception) {
                        false
                    }
                } ?: false

                if (!hasExistingRegion) {
                    if ((offlineRegions?.size ?: 0) >= 5) {
                        offlineRegions?.firstOrNull()?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {}
                            override fun onError(error: String) {}
                        })
                    }
                    val metadata = "CatNav Local Region".toByteArray(Charsets.UTF_8)
                    offlineManager.createOfflineRegion(
                        definition,
                        metadata,
                        object : OfflineManager.CreateOfflineRegionCallback {
                            override fun onCreate(offlineRegion: OfflineRegion) {
                                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                            }
                            override fun onError(error: String) {}
                        }
                    )
                }
            }

            override fun onError(error: String) {}
        })
    } catch (_: Exception) {
        // Fall back to automatic ambient caching
    }
}

private fun formatMapTimestamp(timestampMs: Long): String =
    DateTimeFormatter.ofPattern("EEEE, d MMM · HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestampMs))

// Default center: Bergen / West Coast of Norway
private const val DEFAULT_MAP_ZOOM = 10.0
private const val DEFAULT_LATITUDE = 60.3913
private const val DEFAULT_LONGITUDE = 5.3221
private const val MIN_COORDINATE_SPREAD = 0.0002
private const val MAP_FIT_PADDING_PX = 90
