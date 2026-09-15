package com.grant.routecollector.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import com.grant.routecollector.data.AppDatabase
import com.grant.routecollector.data.MarkerEntity
import com.grant.routecollector.data.TrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Borderless route-marker map that visually forms the lower portion of the
 * Route Collector overlay. There is deliberately no independent title bar,
 * background panel, drag handle, or other window chrome.
 */
class RouteMarkerMapOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "routecollector.MARKER_MAP_SHOW"
        const val ACTION_HIDE = "routecollector.MARKER_MAP_HIDE"
    }

    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.get(this).dao() }
    private var mapView: MapView? = null
    private var latestMarkers: List<MarkerEntity> = emptyList()

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = "RouteCollector/0.1 Android"
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope.launch {
            combine(dao.observeAllMarkers(), TrackingState.latestLat, TrackingState.latestLon, TrackingState.latestBearingDegrees) { markers, lat, lon, bearing ->
                MapState(markers, lat, lon, bearing)
            }.collect { state ->
                latestMarkers = state.markers
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
            setMultiTouchControls(false)
            isClickable = false
            isFocusable = false
            controller.setZoom(17.5)
        }

        // Align directly below the compact Route Collector controls. The map
        // has no independent window chrome, so to the driver it reads as one
        // Route Collector overlay rather than a second "Route markers" panel.
        val params = WindowManager.LayoutParams(
            360,
            260,
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
        map.onResume()
        render(MapState(latestMarkers, TrackingState.latestLat.value, TrackingState.latestLon.value, TrackingState.latestBearingDegrees.value))
    }

    private fun render(state: MapState) {
        val map = mapView ?: return
        val lat = state.lat ?: return
        val lon = state.lon ?: return
        val here = GeoPoint(lat, lon)
        map.controller.setCenter(here)
        map.mapOrientation = -(state.bearing ?: 0f)
        map.overlays.clear()

        map.overlays.add(Marker(map).apply {
            position = here
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "You"
            icon = BadgeDrawable("●", 0xFF2196F3.toInt())
        })

        state.markers.asSequence()
            .filter { distanceMetres(lat, lon, it.latitude, it.longitude) <= 5_000f }
            .forEach { fact ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(fact.latitude, fact.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = markerTitle(fact)
                    snippet = fact.note
                    icon = markerBadge(fact)
                })
            }
        map.invalidate()
    }

    private fun markerTitle(fact: MarkerEntity): String = when (fact.kind) {
        "speed" -> "Speed zone: ${speedFrom(fact)}"
        "speed_advance" -> "Speed ahead: ${speedFrom(fact)}"
        "red_light_camera", "camera" -> "Red-light camera"
        "community_safety_zone_start" -> "Community zone start"
        "community_safety_zone_end" -> "Community zone end"
        "senior_safety_zone_start" -> "Senior zone start"
        "senior_safety_zone_end" -> "Senior zone end"
        else -> fact.kind.replace('_', ' ')
    }

    private fun markerBadge(fact: MarkerEntity): Drawable = when (fact.kind) {
        "speed" -> BadgeDrawable(speedFrom(fact), 0xFF1565C0.toInt())
        "speed_advance" -> BadgeDrawable("A${speedFrom(fact)}", 0xFF00897B.toInt())
        "red_light_camera", "camera" -> BadgeDrawable("C", 0xFFC62828.toInt())
        "community_safety_zone_start" -> BadgeDrawable("C+", 0xFFEF6C00.toInt())
        "community_safety_zone_end" -> BadgeDrawable("C−", 0xFFEF6C00.toInt())
        "senior_safety_zone_start" -> BadgeDrawable("S+", 0xFF6A1B9A.toInt())
        "senior_safety_zone_end" -> BadgeDrawable("S−", 0xFF6A1B9A.toInt())
        else -> BadgeDrawable("•", 0xFF616161.toInt())
    }

    private fun speedFrom(fact: MarkerEntity): String = Regex("\\b(40|50|60|70|80|90|100|110)\\b").find(fact.note)?.value ?: "?"

    private fun distanceMetres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val out = FloatArray(1)
        android.location.Location.distanceBetween(aLat, aLon, bLat, bLon, out)
        return out[0]
    }

    override fun onDestroy() {
        mapView?.onPause()
        mapView?.let { windowManager.removeView(it) }
        mapView = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class MapState(val markers: List<MarkerEntity>, val lat: Double?, val lon: Double?, val bearing: Float?)

    private class BadgeDrawable(private val label: String, private val fill: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        init { setBounds(0, 0, 54, 54) }
        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
            paint.color = Color.WHITE; canvas.drawCircle(cx, cy, 25f, paint)
            paint.color = fill; canvas.drawCircle(cx, cy, 21f, paint)
            paint.color = Color.WHITE; paint.textSize = if (label.length > 3) 13f else 16f
            canvas.drawText(label, cx, cy + 5f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = 54
        override fun getIntrinsicHeight(): Int = 54
    }
}
