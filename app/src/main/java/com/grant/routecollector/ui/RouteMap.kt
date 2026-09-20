package com.grant.routecollector.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.grant.routecollector.R
import com.grant.routecollector.data.MarkerEntity
import com.grant.routecollector.data.TrackPointEntity
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.drawable.DrawableCompat

private const val AHEAD_DISTANCE_METRES = 5000f
private const val AHEAD_BEARING_DEGREES = 70f

@Composable
fun RouteMap(
    points: List<TrackPointEntity>,
    markers: List<MarkerEntity>,
    currentLat: Double?,
    currentLon: Double?,
    travelBearing: Float?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            MapView(context).apply {
                setMultiTouchControls(true)
                controller.setZoom(16.0)
            }
        },
        update = { map ->
            val previousCenter = map.mapCenter
            map.overlays.clear()

            if (points.isNotEmpty()) {
                val geo = points.map { GeoPoint(it.latitude, it.longitude) }
                map.overlays.add(Polyline().apply { setPoints(geo) })
            }

            val current = if (currentLat != null && currentLon != null) GeoPoint(currentLat, currentLon) else null
            if (current != null) {
                map.overlays.add(Marker(map).apply {
                    position = current
                    title = "You"
                    icon = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.rgb(33, 150, 243))
                        setStroke(4, Color.WHITE)
                        setSize(28, 28)
                    }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
                travelBearing?.let { bearing -> map.mapOrientation = -bearing }
                map.post {
                    if (map.width > 0 && map.height > 0) {
                        val projection = map.projection
                        val screen = projection.toPixels(current, Point())
                        val targetY = (map.height * 0.72f).toInt()
                        val desiredCenter = projection.fromPixels(screen.x, screen.y - targetY + map.height / 2)
                        map.controller.setCenter(desiredCenter)
                    }
                }
            } else if (points.isNotEmpty()) {
                map.controller.setCenter(GeoPoint(points.last().latitude, points.last().longitude))
            }

            val visibleMarkers = if (current == null || travelBearing == null) {
                markers
            } else {
                markers.filter { marker ->
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(
                        current.latitude, current.longitude,
                        marker.latitude, marker.longitude, distance
                    )
                    if (distance[0] > AHEAD_DISTANCE_METRES) return@filter false
                    val from = android.location.Location("current").apply {
                        latitude = current.latitude
                        longitude = current.longitude
                    }
                    val to = android.location.Location("marker").apply {
                        latitude = marker.latitude
                        longitude = marker.longitude
                    }
                    bearingDifference(travelBearing, from.bearingTo(to)) <= AHEAD_BEARING_DEGREES
                }
            }

            val rankedMarkers = visibleMarkers.sortedBy { marker ->
                if (current == null) Float.MAX_VALUE else FloatArray(1).also { android.location.Location.distanceBetween(current.latitude, current.longitude, marker.latitude, marker.longitude, it) }[0]
            }
            rankedMarkers.forEachIndexed { index, m ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(m.latitude, m.longitude)
                    title = when (m.kind) {
                        "camera", "red_light_camera" -> "Red light camera"
                        "speed" -> "Speed zone"
                        "speed_advance" -> "Speed change ahead"
                        "deer_zone_enter" -> "Deer crossing area"
                        "school_zone", "school_zone_start", "community_safety_zone_start" -> "Community safety zone start"
                        "school_zone_end", "community_safety_zone_end" -> "Community safety zone end"
                        "senior_safety_zone_start" -> "Senior safety zone start"
                        "senior_safety_zone_end" -> "Senior safety zone end"
                        else -> m.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    }
                    snippet = if (index == 0 && current != null) {
                        val d = FloatArray(1); android.location.Location.distanceBetween(current.latitude, current.longitude, m.latitude, m.longitude, d)
                        "NEXT • ${d[0].roundToInt()} m • ${m.note}"
                    } else m.note
                    if (m.kind == "camera" || m.kind == "red_light_camera") {
                        icon = ContextCompat.getDrawable(map.context, R.drawable.ic_red_light_camera)?.apply {
                            setBounds(0, 0, (56 * map.context.resources.displayMetrics.density).toInt(), (56 * map.context.resources.displayMetrics.density).toInt())
                        }
                    } else if (m.kind == "deer_zone_enter") {
                        icon = emojiMarker("🦌", map.context.resources.displayMetrics.density)
                    } else if (m.kind in setOf("community_safety_zone_start","community_safety_zone_end","school_zone","school_zone_start","school_zone_end")) {
                        icon = zoneMarker(Color.rgb(46,125,50), map.context.resources.displayMetrics.density)
                    } else if (m.kind in setOf("senior_safety_zone_start","senior_safety_zone_end")) {
                        icon = zoneMarker(Color.rgb(106,76,147), map.context.resources.displayMetrics.density)
                    } else if (m.kind == "speed" || m.kind == "speed_advance") {
                        icon = zoneMarker(Color.rgb(245,124,0), map.context.resources.displayMetrics.density)
                    }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            map.invalidate()
        }
    )
}

private fun bearingDifference(a: Float, b: Float): Float {
    fun normalize(value: Float) = ((value % 360f) + 360f) % 360f
    val raw = abs(normalize(a) - normalize(b))
    return if (raw > 180f) 360f - raw else raw
}


private fun zoneMarker(color: Int, density: Float): Drawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke((3 * density).toInt(), Color.WHITE)
    setSize((30 * density).toInt(), (30 * density).toInt())
}

private fun emojiMarker(emoji: String, density: Float): Drawable {
    val size = (44 * density).toInt()
    return object : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34 * density
            textAlign = android.graphics.Paint.Align.CENTER
        }
        override fun draw(canvas: android.graphics.Canvas) {
            val fm = paint.fontMetrics
            canvas.drawText(emoji, bounds.exactCenterX(), bounds.exactCenterY() - (fm.ascent + fm.descent) / 2, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
    }.apply { setBounds(0, 0, size, size) }
}
