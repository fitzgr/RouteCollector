package com.grant.routecollector.ui

import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import com.grant.routecollector.data.MarkerEntity
import com.grant.routecollector.data.TrackPointEntity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import kotlin.math.abs

private const val AHEAD_DISTANCE_METRES = 5000f
private const val AHEAD_BEARING_DEGREES = 70f

@Composable
fun RouteMap(
    points: List<TrackPointEntity>,
    markers: List<MarkerEntity>,
    currentLat: Double?,
    currentLon: Double?,
    travelBearing: Float?,
    activeZones: Set<String>,
    postedSpeed: Int?,
    collectedSpeedActive: Boolean,
    activeRoadAlerts: Set<String>,
    actualSpeedKph: Float?,
    fitRoute: Boolean = false,
    onHistoryPointSelected: ((TrackPointEntity) -> Unit)? = null,
    historySegmentStart: TrackPointEntity? = null,
    onHistoryMarkerSelected: ((MarkerEntity) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            MapView(context).apply {
                onCreate(null)
                onResume()
                getMapAsync { map ->
                    map.uiSettings.isZoomControlsEnabled = false
                    map.uiSettings.isCompassEnabled = true
                    map.uiSettings.isMapToolbarEnabled = false
                }
            }
        },
        update = { mapView ->
            mapView.getMapAsync { map ->
                map.clear()
                map.uiSettings.isZoomControlsEnabled = false
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isMapToolbarEnabled = false

                if (points.isNotEmpty()) {
                    map.addPolyline(
                        PolylineOptions()
                            .addAll(points.map { LatLng(it.latitude, it.longitude) })
                            .width(6f)
                            .color(Color.rgb(30, 136, 229))
                    )
                }

                val current = if (currentLat != null && currentLon != null) LatLng(currentLat, currentLon) else null
                if (current != null && !fitRoute) {
                    val zoneColor = when {
                        "community" in activeZones -> Color.rgb(46, 125, 50)
                        "senior" in activeZones -> Color.rgb(106, 76, 147)
                        "deer" in activeRoadAlerts -> Color.rgb(255, 193, 7)
                        collectedSpeedActive && postedSpeed != null -> Color.rgb(245, 124, 0)
                        else -> null
                    }
                    zoneColor?.let {
                        map.addCircle(
                            com.google.android.gms.maps.model.CircleOptions()
                                .center(current).radius(18.0)
                                .strokeWidth(7f).strokeColor(it)
                                .fillColor(Color.TRANSPARENT)
                        )
                    }
                    map.addMarker(
                        MarkerOptions().position(current).title("You")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    )
                    val zoom = speedSensitiveZoom(actualSpeedKph).toFloat()
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(current)
                                .zoom(zoom)
                                .bearing(travelBearing ?: 0f)
                                .tilt(0f)
                                .build()
                        )
                    )
                } else if (fitRoute && points.size >= 2) {
                    val bounds = LatLngBounds.builder()
                    points.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                    mapView.post {
                        if (mapView.width > 0 && mapView.height > 0) {
                            runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 64)) }
                        }
                    }
                    map.addMarker(MarkerOptions().position(LatLng(points.first().latitude, points.first().longitude)).title("Start").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
                    map.addMarker(MarkerOptions().position(LatLng(points.last().latitude, points.last().longitude)).title("End").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))
                    if (onHistoryPointSelected != null) {
                        val step = (points.size / 12).coerceAtLeast(1)
                        points.filterIndexed { index, _ -> index % step == 0 }.forEach { point ->
                            val selected = historySegmentStart?.id == point.id
                            map.addMarker(
                                MarkerOptions().position(LatLng(point.latitude, point.longitude))
                                    .title(if (selected) "Segment start selected" else "Edit road segment here")
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                            )?.tag = point
                        }
                    }
                } else if (points.isNotEmpty()) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points.last().latitude, points.last().longitude), 16f))
                }

                val visibleMarkers = if (current == null || travelBearing == null || fitRoute) markers else markers.filter { marker ->
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(current.latitude, current.longitude, marker.latitude, marker.longitude, distance)
                    if (distance[0] > AHEAD_DISTANCE_METRES) return@filter false
                    val from = android.location.Location("current").apply { latitude = current.latitude; longitude = current.longitude }
                    val to = android.location.Location("marker").apply { latitude = marker.latitude; longitude = marker.longitude }
                    val inForwardCone = bearingDifference(travelBearing, from.bearingTo(to)) <= AHEAD_BEARING_DEGREES
                    val capturedBearing = Regex("bearing=(-?\\d+)").find(marker.note)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.takeIf { it >= 0f }
                    inForwardCone && (capturedBearing == null || bearingDifference(travelBearing, capturedBearing) <= AHEAD_BEARING_DEGREES)
                }

                visibleMarkers.forEach { marker ->
                    val hue = when (marker.kind) {
                        "camera", "red_light_camera" -> BitmapDescriptorFactory.HUE_RED
                        "pedestrian_crossing" -> BitmapDescriptorFactory.HUE_YELLOW
                        "community_safety_zone_start", "community_safety_zone_end", "school_zone", "school_zone_start", "school_zone_end" -> BitmapDescriptorFactory.HUE_GREEN
                        "senior_safety_zone_start", "senior_safety_zone_end" -> BitmapDescriptorFactory.HUE_VIOLET
                        "speed", "speed_advance" -> BitmapDescriptorFactory.HUE_ORANGE
                        "deer_zone_enter" -> BitmapDescriptorFactory.HUE_YELLOW
                        else -> BitmapDescriptorFactory.HUE_AZURE
                    }
                    val title = when (marker.kind) {
                        "camera", "red_light_camera" -> "Red light camera"
                        "speed" -> "Speed zone"
                        "speed_advance" -> "Speed change ahead"
                        "deer_zone_enter" -> "Deer crossing area"
                        "pedestrian_crossing" -> "Pedestrian crossing"
                        "school_zone", "school_zone_start", "community_safety_zone_start" -> "Community safety zone start"
                        "school_zone_end", "community_safety_zone_end" -> "Community safety zone end"
                        "senior_safety_zone_start" -> "Senior safety zone start"
                        "senior_safety_zone_end" -> "Senior safety zone end"
                        else -> marker.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    }
                    map.addMarker(
                        MarkerOptions().position(LatLng(marker.latitude, marker.longitude))
                            .title(title).snippet(marker.note)
                            .icon(BitmapDescriptorFactory.defaultMarker(hue))
                    )?.tag = marker
                }

                if (fitRoute && (onHistoryPointSelected != null || onHistoryMarkerSelected != null)) {
                    map.setOnMarkerClickListener { googleMarker ->
                        when (val tag = googleMarker.tag) {
                            is TrackPointEntity -> { onHistoryPointSelected?.invoke(tag); true }
                            is MarkerEntity -> { onHistoryMarkerSelected?.invoke(tag); true }
                            else -> false
                        }
                    }
                } else {
                    map.setOnMarkerClickListener(null)
                }
            }
        }
    )
}

private fun bearingDifference(a: Float, b: Float): Float {
    fun normalize(value: Float) = ((value % 360f) + 360f) % 360f
    val raw = abs(normalize(a) - normalize(b))
    return if (raw > 180f) 360f - raw else raw
}

private fun speedSensitiveZoom(speedKph: Float?): Double {
    val speed = (speedKph ?: 100f).coerceIn(0f, 110f)
    return when {
        speed <= 30f -> 18.0
        speed <= 50f -> 18.0 - ((speed - 30f) / 20f) * 0.8
        speed <= 80f -> 17.2 - ((speed - 50f) / 30f) * 0.8
        speed <= 100f -> 16.4 - ((speed - 80f) / 20f) * 0.4
        else -> 16.0
    }
}
