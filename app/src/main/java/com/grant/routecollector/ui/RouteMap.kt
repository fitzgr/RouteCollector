package com.grant.routecollector.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
                    title = m.kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    snippet = m.note
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            map.invalidate()
        }
    )
}
