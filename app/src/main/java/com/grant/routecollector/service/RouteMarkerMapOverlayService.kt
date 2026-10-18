package com.grant.routecollector.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import com.grant.routecollector.data.AppDatabase
import com.grant.routecollector.data.MarkerEntity
import com.grant.routecollector.data.TrackingState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class RouteMarkerMapOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "routecollector.MARKER_MAP_SHOW"
        const val ACTION_HIDE = "routecollector.MARKER_MAP_HIDE"
    }

    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.get(this).dao() }
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var latestState = MapState(emptyList(), null, null, null)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope.launch {
            combine(
                dao.observeAllMarkers(),
                TrackingState.latestLat,
                TrackingState.latestLon,
                TrackingState.latestBearingDegrees
            ) { markers, lat, lon, bearing -> MapState(markers, lat, lon, bearing) }
                .collect { state ->
                    latestState = state
                    mapView?.post { render(state) }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) stopSelf() else showOverlay()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (mapView != null) return
        val map = MapView(this).apply {
            onCreate(null)
            onResume()
            isClickable = false
            isFocusable = false
            getMapAsync { readyMap ->
                googleMap = readyMap
                readyMap.uiSettings.isScrollGesturesEnabled = false
                readyMap.uiSettings.isZoomGesturesEnabled = false
                readyMap.uiSettings.isRotateGesturesEnabled = false
                readyMap.uiSettings.isTiltGesturesEnabled = false
                readyMap.uiSettings.isMapToolbarEnabled = false
                render(latestState)
            }
        }
        val params = WindowManager.LayoutParams(
            360, 260,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 430
        }
        mapView = map
        windowManager.addView(map, params)
    }

    private fun render(state: MapState) {
        val map = googleMap ?: return
        val lat = state.lat ?: return
        val lon = state.lon ?: return
        val here = LatLng(lat, lon)
        map.clear()
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(here)
                    .zoom(17.5f)
                    .bearing(state.bearing ?: 0f)
                    .build()
            )
        )
        map.addMarker(
            MarkerOptions().position(here).title("You")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        state.markers.asSequence()
            .filter { distanceMetres(lat, lon, it.latitude, it.longitude) <= 5_000f }
            .forEach { fact ->
                map.addMarker(
                    MarkerOptions()
                        .position(LatLng(fact.latitude, fact.longitude))
                        .title(markerTitle(fact))
                        .snippet(fact.note)
                        .icon(BitmapDescriptorFactory.defaultMarker(markerHue(fact)))
                )
            }
    }

    private fun markerTitle(fact: MarkerEntity): String = when (fact.kind) {
        "speed" -> "Speed zone: ${speedFrom(fact)}"
        "speed_advance" -> "Speed ahead: ${speedFrom(fact)}"
        "red_light_camera", "camera" -> "Red-light camera"
        "community_safety_zone_start" -> "Community zone start"
        "community_safety_zone_end" -> "Community zone end"
        "senior_safety_zone_start" -> "Senior zone start"
        "senior_safety_zone_end" -> "Senior zone end"
        "deer_zone_enter" -> "Deer crossing boundary"
        else -> fact.kind.replace('_', ' ')
    }

    private fun markerHue(fact: MarkerEntity): Float = when (fact.kind) {
        "speed" -> BitmapDescriptorFactory.HUE_BLUE
        "speed_advance" -> BitmapDescriptorFactory.HUE_CYAN
        "red_light_camera", "camera" -> BitmapDescriptorFactory.HUE_RED
        "community_safety_zone_start", "community_safety_zone_end" -> BitmapDescriptorFactory.HUE_ORANGE
        "senior_safety_zone_start", "senior_safety_zone_end" -> BitmapDescriptorFactory.HUE_VIOLET
        "deer_zone_enter" -> BitmapDescriptorFactory.HUE_GREEN
        else -> BitmapDescriptorFactory.HUE_AZURE
    }

    private fun speedFrom(fact: MarkerEntity): String =
        Regex("\\b(40|50|60|70|80|90|100|110)\\b").find(fact.note)?.value ?: "?"

    private fun distanceMetres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val out = FloatArray(1)
        android.location.Location.distanceBetween(aLat, aLon, bLat, bLon, out)
        return out[0]
    }

    override fun onDestroy() {
        mapView?.onPause()
        mapView?.onDestroy()
        mapView?.let { windowManager.removeView(it) }
        googleMap = null
        mapView = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class MapState(
        val markers: List<MarkerEntity>,
        val lat: Double?,
        val lon: Double?,
        val bearing: Float?
    )
}
