package com.grant.routecollector.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.grant.routecollector.data.AppDatabase
import com.grant.routecollector.data.MarkerEntity
import com.grant.routecollector.data.TrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CollectorOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "routecollector.OVERLAY_SHOW"
        const val ACTION_HIDE = "routecollector.OVERLAY_HIDE"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.get(this).dao() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlay()
            else -> showOverlay()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 12, 18, 12)
            setBackgroundColor(0xDD202124.toInt())
        }
        val title = TextView(this).apply {
            text = "Route Collector"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        val camera = Button(this).apply { text = "Mark camera" }
        val schoolZone = Button(this).apply {
            text = "Mark school zone"
            setOnClickListener { saveRoadFact("school_zone", "School zone") }
        }
        val undoCamera = Button(this).apply {
            text = "Undo camera"
            isEnabled = false
            setOnClickListener {
                undoLatestCameraMarker()
                isEnabled = false
            }
        }
        val hide = Button(this).apply {
            text = "Hide"
            setOnClickListener { stopSelf() }
        }
        camera.setOnClickListener {
            saveRoadFact("camera", "Camera intersection") {
                undoCamera.isEnabled = true
            }
        }
        panel.addView(title)
        panel.addView(camera)
        panel.addView(schoolZone)
        panel.addView(undoCamera)
        panel.addView(hide)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 220
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        panel.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX - (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(panel, params)
                    true
                }
                else -> false
            }
        }

        overlayView = panel
        windowManager.addView(panel, params)
    }

    private fun saveRoadFact(kind: String, note: String, onSaved: () -> Unit = {}) {
        val driveId = TrackingState.activeDriveId.value
        if (driveId == null) {
            Toast.makeText(this, "Start a drive in Route Collector first", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val point = dao.latestPoint(driveId)
            if (point == null) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@CollectorOverlayService, "Waiting for GPS", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            dao.insertMarker(
                MarkerEntity(
                    driveId = driveId,
                    timestamp = System.currentTimeMillis(),
                    latitude = point.latitude,
                    longitude = point.longitude,
                    kind = kind,
                    note = note
                )
            )
            launch(Dispatchers.Main) {
                val message = if (kind == "camera") "Camera marked — Undo available" else "School zone marked"
                Toast.makeText(this@CollectorOverlayService, message, Toast.LENGTH_SHORT).show()
                onSaved()
            }
        }
    }

    private fun undoLatestCameraMarker() {
        val driveId = TrackingState.activeDriveId.value
        if (driveId == null) {
            Toast.makeText(this, "No active drive", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val deleted = dao.deleteLatestCameraMarker(driveId)
            launch(Dispatchers.Main) {
                Toast.makeText(
                    this@CollectorOverlayService,
                    if (deleted > 0) "Last camera marker removed" else "No camera marker to remove",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun hideOverlay() {
        stopSelf()
    }

    override fun onDestroy() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
