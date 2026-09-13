package com.grant.routecollector.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.grant.routecollector.R
import com.grant.routecollector.data.MarkerEntity
import com.grant.routecollector.data.TrackPointEntity
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun RouteMap(
    points: List<TrackPointEntity>,
    markers: List<MarkerEntity>,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).apply {
                setMultiTouchControls(true)
                controller.setZoom(16.0)
            }
        },
        update = { map ->
            map.overlays.clear()
            if (points.isNotEmpty()) {
                val geo = points.map { GeoPoint(it.latitude, it.longitude) }
                map.overlays.add(Polyline().apply { setPoints(geo) })
                map.controller.setCenter(geo.last())
            }
            markers.forEach { m ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(m.latitude, m.longitude)
                    title = when (m.kind) {
                        "red_light_camera" -> "Red light camera"
                        "community_safety_zone_start" -> "Community safety zone start"
                        "community_safety_zone_end" -> "Community safety zone end"
                        "senior_safety_zone_start" -> "Senior safety zone start"
                        "senior_safety_zone_end" -> "Senior safety zone end"
                        else -> m.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    }
                    snippet = m.note
                    if (m.kind == "red_light_camera") {
                        icon = ContextCompat.getDrawable(map.context, R.drawable.ic_red_light_camera)
                    }
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            map.invalidate()
        }
    )
}
