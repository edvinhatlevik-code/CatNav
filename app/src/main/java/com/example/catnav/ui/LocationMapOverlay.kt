package com.example.catnav.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import com.example.catnav.data.LocationRecord
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

internal class LocationMapOverlay(
    private val records: List<LocationRecord>,
    private val heatMap: Boolean,
    private val onLocationSelected: (LocationRecord) -> Unit
) : Overlay() {
    private val projectedPoints = mutableListOf<Point>()
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ROUTE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ROUTE_COLOR
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ARROW_COLOR
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LATEST_COLOR
        style = Paint.Style.FILL
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FF7043
        style = Paint.Style.FILL
    }
    private val heatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || records.isEmpty()) return
        updateProjectedPoints(mapView)
        if (heatMap) {
            drawHeatMap(canvas)
        } else {
            drawTrace(canvas)
        }
        drawLatestMarker(canvas)
    }

    override fun onSingleTapConfirmed(event: MotionEvent, mapView: MapView): Boolean {
        if (records.isEmpty()) return false
        updateProjectedPoints(mapView)
        val selected = projectedPoints.indices
            .map { index ->
                index to hypot(
                        (event.x - projectedPoints[index].x).toDouble(),
                        (event.y - projectedPoints[index].y).toDouble()
                )
            }
            .filter { it.second <= TAP_RADIUS_PX }
            .minByOrNull { it.second }
            ?: return false
        onLocationSelected(records[selected.first])
        return true
    }

    private fun updateProjectedPoints(mapView: MapView) {
        projectedPoints.clear()
        records.forEach { record ->
            val point = Point()
            mapView.projection.toPixels(GeoPoint(record.latitude, record.longitude), point)
            projectedPoints.add(point)
        }
    }

    private fun drawTrace(canvas: Canvas) {
        if (projectedPoints.isEmpty()) return
        if (projectedPoints.size > 1) {
            val route = Path().apply {
                moveTo(projectedPoints.first().x.toFloat(), projectedPoints.first().y.toFloat())
                projectedPoints.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
            }
            canvas.drawPath(route, routePaint)
            drawDirectionArrows(canvas)
        }
        projectedPoints.forEach { canvas.drawCircle(it.x.toFloat(), it.y.toFloat(), 5f, pointPaint) }
    }

    private fun drawDirectionArrows(canvas: Canvas) {
        val interval = (projectedPoints.size / 12).coerceAtLeast(1)
        for (index in 1 until projectedPoints.size step interval) {
            val start = projectedPoints[index - 1]
            val end = projectedPoints[index]
            val dx = (end.x - start.x).toFloat()
            val dy = (end.y - start.y).toFloat()
            val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (length < 18f) continue
            val directionX = dx / length
            val directionY = dy / length
            val centerX = (start.x + end.x) / 2f
            val centerY = (start.y + end.y) / 2f
            val baseX = centerX - directionX * 7f
            val baseY = centerY - directionY * 7f
            val perpendicularX = -directionY * 5f
            val perpendicularY = directionX * 5f
            val arrow = Path().apply {
                moveTo(centerX + directionX * 8f, centerY + directionY * 8f)
                lineTo(baseX + perpendicularX, baseY + perpendicularY)
                lineTo(baseX - perpendicularX, baseY - perpendicularY)
                close()
            }
            canvas.drawPath(arrow, arrowPaint)
        }
    }

    private fun drawHeatMap(canvas: Canvas) {
        projectedPoints.forEach { point ->
            heatPaint.shader = RadialGradient(
                point.x.toFloat(),
                point.y.toFloat(),
                HEAT_RADIUS_PX,
                intArrayOf(HEAT_CORE_COLOR, HEAT_MID_COLOR, HEAT_EDGE_COLOR),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), HEAT_RADIUS_PX, heatPaint)
        }
        heatPaint.shader = null
    }

    private fun drawLatestMarker(canvas: Canvas) {
        val latest = projectedPoints.lastOrNull() ?: return
        canvas.drawCircle(latest.x.toFloat(), latest.y.toFloat(), 22f, haloPaint)
        canvas.drawCircle(latest.x.toFloat(), latest.y.toFloat(), 12f, markerPaint)
        canvas.drawCircle(latest.x.toFloat(), latest.y.toFloat(), 5f, pointPaint.apply { color = 0xFFFFFFFF.toInt() })
        pointPaint.color = ROUTE_COLOR
    }

    companion object {
        private const val ROUTE_COLOR = 0xFF087E72.toInt()
        private const val ARROW_COLOR = 0xFF064E47.toInt()
        private const val LATEST_COLOR = 0xFFE66B47.toInt()
        private const val HEAT_CORE_COLOR = 0xA7F35F4B.toInt()
        private const val HEAT_MID_COLOR = 0x70F4A349.toInt()
        private const val HEAT_EDGE_COLOR = 0x00F4A349
        private const val HEAT_RADIUS_PX = 46f
        private const val TAP_RADIUS_PX = 36f
    }
}
