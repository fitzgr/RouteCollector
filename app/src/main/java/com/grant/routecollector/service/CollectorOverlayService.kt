package com.grant.routecollector.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
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
import java.util.Locale

class CollectorOverlayService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_SHOW = "routecollector.OVERLAY_SHOW"
        const val ACTION_HIDE = "routecollector.OVERLAY_HIDE"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.get(this).dao() }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var highwayMode = true

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CANADA
            ttsReady = true
        }
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
        val mode = Button(this).apply { text = "Mode: Highway" }
        val speedChange = Button(this).apply { text = "Mark speed change" }
        val speedGrid = GridLayout(this).apply {
            columnCount = 3
            visibility = View.GONE
        }
        val camera = Button(this).apply { text = "Mark camera" }
        val schoolStart = Button(this).apply { text = "School zone start" }
        val schoolEnd = Button(this).apply { text = "School zone end" }
        val undoCamera = Button(this).apply {
            text = "Undo camera"
            isEnabled = false
        }
        val hide = Button(this).apply {
            text = "Hide"
            setOnClickListener { stopSelf() }
        }

        fun rebuildSpeedButtons() {
            speedGrid.removeAllViews()
            val speeds = if (highwayMode) listOf(60, 70, 80, 90, 100, 110) else listOf(30, 40, 50, 60, 70, 80)
            for (speed in speeds) {
                val button = Button(this).apply {
                    text = speed.toString()
                    setOnClickListener {
                        saveRoadFact("speed", "Speed limit $speed") {
                            speak("Speed $speed marked")
                            speedGrid.visibility = View.GONE
                        }
                    }
                }
                speedGrid.addView(button)
            }
        }

        mode.setOnClickListener {
            highwayMode = !highwayMode
            mode.text = if (highwayMode) "Mode: Highway" else "Mode: Local"
            rebuildSpeedButtons()
            speak(if (highwayMode) "Highway speed capture" else "Local road speed capture")
        }
        speedChange.setOnClickListener {
            if (speedGrid.visibility == View.VISIBLE) {
                speedGrid.visibility = View.GONE
            } else {
                rebuildSpeedButtons()
                speedGrid.visibility = View.VISIBLE
                speak("Select new posted speed")
            }
        }
        camera.setOnClickListener {
            saveRoadFact("camera", "Camera intersection") {
                undoCamera.isEnabled = true
                speak("Camera marked")
            }
        }
        schoolStart.setOnClickListener {
            saveRoadFact("school_zone_start", "School zone start") { speak("School zone start marked") }
        }
        schoolEnd.setOnClickListener {
            saveRoadFact("school_zone_end", "School zone end") { speak("School zone end marked") }
        }
        undoCamera.setOnClickListener {
            undoLatestCameraMarker()
            undoCamera.isEnabled = false
        }

        panel.addView(title)
        panel.addView(mode)
        panel.addView(speedChange)
        panel.addView(speedGrid)
        panel.addView(camera)
        panel.addView(schoolStart)
        panel.addView(schoolEnd)
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
            y = 180
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
                Toast.makeText(this@CollectorOverlayService, "$note marked at GPS position", Toast.LENGTH_SHORT).show()
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
                if (deleted > 0) speak("Camera marker removed")
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "overlay-${System.currentTimeMillis()}")
    }

    private fun hideOverlay() {
        stopSelf()
    }

    override fun onDestroy() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        tts?.stop()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
