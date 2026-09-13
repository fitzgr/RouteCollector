package com.grant.routecollector.service

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.grant.routecollector.data.AppDatabase
import com.grant.routecollector.data.DriverAlert
import com.grant.routecollector.data.MarkerEntity
import com.grant.routecollector.data.TrackingState
import com.grant.routecollector.map.IntersectionSnapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class CollectorOverlayService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_SHOW = "routecollector.OVERLAY_SHOW"
        const val ACTION_HIDE = "routecollector.OVERLAY_HIDE"
        private const val PREFS = "routecollector_overlay"
        private const val PREF_VISUAL_ALERTS = "visual_alerts_enabled"
        private const val PREF_CAMERA_WARNING_METRES = "red_light_camera_warning_metres"
        private const val PREF_SPEED_TOLERANCE_PREFIX = "speed_tolerance_"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var overlayView: View? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.get(this).dao() }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var handsFreeEnabled = true
    private var suppressRestartUntilSpeechDone = false
    private var visualAlertsEnabled = true
    private var listeningStatus: TextView? = null
    private var visualAlertView: TextView? = null
    private var speedStatusView: TextView? = null
    private var settingsPanel: LinearLayout? = null
    private var speedMarkerPanel: LinearLayout? = null
    private var verificationActions: LinearLayout? = null
    private var activeVerificationMarkerId: Long? = null

    private val clearVisualAlert = Runnable { visualAlertView?.visibility = View.GONE }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        visualAlertsEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_VISUAL_ALERTS, true)
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()

        scope.launch {
            TrackingState.driverAlert.collect { alert ->
                if (alert != null) mainHandler.post { handleDriverAlert(alert) }
            }
        }
        scope.launch {
            combine(TrackingState.currentPostedSpeed, TrackingState.latestSpeedKph) { posted, actual -> posted to actual }
                .collect { (posted, actual) -> mainHandler.post { updateSpeedStatus(posted, actual) } }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CANADA
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) = releaseAudioFocusAndResumeListening()
                override fun onDone(utteranceId: String?) = releaseAudioFocusAndResumeListening()
            })
            ttsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> stopSelf()
            else -> showOverlay()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xE6202124.toInt())
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = "Route Collector"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val settings = Button(this).apply { text = "⚙" }
        val hide = Button(this).apply { text = "×"; setOnClickListener { stopSelf() } }
        titleRow.addView(title)
        titleRow.addView(settings)
        titleRow.addView(hide)

        speedStatusView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(6, 6, 6, 6)
            text = "Posted --   Actual --"
        }
        listeningStatus = TextView(this).apply {
            text = "🎙 Route: starting…"
            setTextColor(0xFFB8E986.toInt())
            textSize = 12f
        }
        val grammar = TextView(this).apply {
            text = "Route <action> ::= speed <limit> | red light camera | community/senior safety zone <start|end> | undo"
            setTextColor(0xFFC8C8C8.toInt())
            textSize = 10f
        }
        visualAlertView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF37474F.toInt())
            setPadding(10, 7, 10, 7)
            textSize = 13f
        }
        verificationActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(Button(this@CollectorOverlayService).apply { text = "Keep"; setOnClickListener { keepVerifiedCamera() } })
            addView(Button(this@CollectorOverlayService).apply { text = "Remove"; setOnClickListener { removeVerifiedCamera() } })
        }

        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val handsFree = Button(this).apply { text = "🎙 ON" }
        val redLightCamera = Button(this).apply { text = "🚦 Camera"; setOnClickListener { markRedLightCamera() } }
        val speedMarker = Button(this).apply { text = "Speed" }
        quickRow.addView(handsFree)
        quickRow.addView(redLightCamera)
        quickRow.addView(speedMarker)

        val communityRow = buildZoneRow(
            "Community safety zone",
            onStart = { saveRoadFact("community_safety_zone_start", "Community safety zone start") { acknowledge("Community safety zone start marked") } },
            onEnd = { saveRoadFact("community_safety_zone_end", "Community safety zone end") { acknowledge("Community safety zone end marked") } }
        )
        val seniorRow = buildZoneRow(
            "Senior safety zone",
            onStart = { saveRoadFact("senior_safety_zone_start", "Senior safety zone start") { acknowledge("Senior safety zone start marked") } },
            onEnd = { saveRoadFact("senior_safety_zone_end", "Senior safety zone end") { acknowledge("Senior safety zone end marked") } }
        )

        val undo = Button(this).apply { text = "↶ Undo"; setOnClickListener { undoLatestMarker() } }
        speedMarkerPanel = buildSpeedMarkerPanel()
        settingsPanel = buildSettingsPanel()

        handsFree.setOnClickListener {
            handsFreeEnabled = !handsFreeEnabled
            handsFree.text = if (handsFreeEnabled) "🎙 ON" else "🎙 OFF"
            if (handsFreeEnabled) {
                listeningStatus?.text = "🎙 Route: listening"
                startListeningSoon(150)
            } else {
                speechRecognizer?.cancel()
                listeningStatus?.text = "🎙 Route: off"
            }
        }
        speedMarker.setOnClickListener {
            speedMarkerPanel?.visibility = if (speedMarkerPanel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        settings.setOnClickListener {
            settingsPanel?.visibility = if (settingsPanel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        panel.addView(titleRow)
        panel.addView(speedStatusView)
        panel.addView(listeningStatus)
        panel.addView(grammar)
        panel.addView(visualAlertView)
        panel.addView(verificationActions)
        panel.addView(quickRow)
        panel.addView(speedMarkerPanel)
        panel.addView(communityRow)
        panel.addView(seniorRow)
        panel.addView(undo)
        panel.addView(settingsPanel)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 100
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        panel.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = event.rawX; touchY = event.rawY; false
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
        updateSpeedStatus(TrackingState.currentPostedSpeed.value, TrackingState.latestSpeedKph.value)
        if (handsFreeEnabled) startListeningSoon(300)
    }

    private fun buildZoneRow(label: String, onStart: () -> Unit, onEnd: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val textView = TextView(this@CollectorOverlayService).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(textView)
            addView(Button(this@CollectorOverlayService).apply { text = "▶"; contentDescription = "$label start"; setOnClickListener { onStart() } })
            addView(Button(this@CollectorOverlayService).apply { text = "■"; contentDescription = "$label end"; setOnClickListener { onEnd() } })
        }
    }

    private fun buildSpeedMarkerPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 6, 8, 6)
            setBackgroundColor(0xFF151618.toInt())
        }
        val label = TextView(this).apply { text = "Speed marker"; setTextColor(0xFFFFFFFF.toInt()); textSize = 12f }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val speeds = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
        val speedSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@CollectorOverlayService, android.R.layout.simple_spinner_dropdown_item, speeds.map { "$it km/h" })
            setSelection(speeds.indexOf(60))
        }
        val types = listOf("Zone begins", "Advance sign")
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@CollectorOverlayService, android.R.layout.simple_spinner_dropdown_item, types)
        }
        val submit = Button(this).apply {
            text = "Set"
            setOnClickListener {
                val speed = speeds[speedSpinner.selectedItemPosition]
                val advance = typeSpinner.selectedItemPosition == 1
                val kind = if (advance) "speed_advance" else "speed"
                val note = if (advance) "Speed limit $speed advance sign" else "Speed limit $speed"
                saveRoadFact(kind, note) {
                    if (!advance) TrackingState.currentPostedSpeed.value = speed
                    acknowledge(if (advance) "$speed kilometre advance sign marked" else "$speed kilometre zone start marked")
                }
            }
        }
        row.addView(speedSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(typeSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(submit)
        panel.addView(label)
        panel.addView(row)
        return panel
    }

    private fun buildSettingsPanel(): LinearLayout {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 8, 8, 8)
            setBackgroundColor(0xFF151618.toInt())
        }

        val visualAlerts = Button(this).apply {
            text = if (visualAlertsEnabled) "Visual alerts: ON" else "Visual alerts: OFF"
            setOnClickListener {
                visualAlertsEnabled = !visualAlertsEnabled
                prefs.edit().putBoolean(PREF_VISUAL_ALERTS, visualAlertsEnabled).apply()
                text = if (visualAlertsEnabled) "Visual alerts: ON" else "Visual alerts: OFF"
                if (!visualAlertsEnabled) {
                    mainHandler.removeCallbacks(clearVisualAlert)
                    visualAlertView?.visibility = View.GONE
                } else showVisualAlert("Visual alerts enabled")
            }
        }

        val cameraLabel = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 12f }
        fun refreshCameraDistance() {
            cameraLabel.text = "Camera warning: ${prefs.getInt(PREF_CAMERA_WARNING_METRES, 200)} m"
        }
        refreshCameraDistance()
        val cameraRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cameraRow.addView(Button(this).apply {
            text = "−50"
            setOnClickListener {
                val value = (prefs.getInt(PREF_CAMERA_WARNING_METRES, 200) - 50).coerceAtLeast(100)
                prefs.edit().putInt(PREF_CAMERA_WARNING_METRES, value).apply(); refreshCameraDistance()
            }
        })
        cameraRow.addView(Button(this).apply {
            text = "+50"
            setOnClickListener {
                val value = (prefs.getInt(PREF_CAMERA_WARNING_METRES, 200) + 50).coerceAtMost(500)
                prefs.edit().putInt(PREF_CAMERA_WARNING_METRES, value).apply(); refreshCameraDistance()
            }
        })

        val toleranceTitle = TextView(this).apply { text = "Over-speed warning"; setTextColor(0xFFFFFFFF.toInt()); textSize = 12f }
        val toleranceSpeeds = listOf(30, 40, 50, 60, 70, 80, 90, 100, 110)
        val toleranceSpeed = Spinner(this).apply {
            adapter = ArrayAdapter(this@CollectorOverlayService, android.R.layout.simple_spinner_dropdown_item, toleranceSpeeds.map { "$it km/h" })
            setSelection(toleranceSpeeds.indexOf(80))
        }
        val toleranceLabel = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 12f }
        fun selectedToleranceSpeed() = toleranceSpeeds[toleranceSpeed.selectedItemPosition]
        fun refreshTolerance() {
            val speed = selectedToleranceSpeed()
            toleranceLabel.text = "$speed zone tolerance: +${getSpeedTolerance(speed)} km/h"
        }
        toleranceSpeed.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = refreshTolerance()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
        val toleranceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toleranceRow.addView(Button(this).apply {
            text = "−1"
            setOnClickListener {
                val speed = selectedToleranceSpeed()
                setSpeedTolerance(speed, (getSpeedTolerance(speed) - 1).coerceAtLeast(0)); refreshTolerance(); updateSpeedStatus(TrackingState.currentPostedSpeed.value, TrackingState.latestSpeedKph.value)
            }
        })
        toleranceRow.addView(Button(this).apply {
            text = "+1"
            setOnClickListener {
                val speed = selectedToleranceSpeed()
                setSpeedTolerance(speed, (getSpeedTolerance(speed) + 1).coerceAtMost(20)); refreshTolerance(); updateSpeedStatus(TrackingState.currentPostedSpeed.value, TrackingState.latestSpeedKph.value)
            }
        })
        refreshTolerance()

        val export = Button(this).apply { text = "Export route data"; setOnClickListener { exportRouteData() } }

        panel.addView(visualAlerts)
        panel.addView(cameraLabel)
        panel.addView(cameraRow)
        panel.addView(toleranceTitle)
        panel.addView(toleranceSpeed)
        panel.addView(toleranceLabel)
        panel.addView(toleranceRow)
        panel.addView(export)
        return panel
    }

    private fun defaultTolerance(speed: Int): Int = if (speed >= 100) 9 else 8

    private fun getSpeedTolerance(speed: Int): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE).getInt("$PREF_SPEED_TOLERANCE_PREFIX$speed", defaultTolerance(speed))

    private fun setSpeedTolerance(speed: Int, tolerance: Int) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("$PREF_SPEED_TOLERANCE_PREFIX$speed", tolerance).apply()
    }

    private fun updateSpeedStatus(posted: Int?, actual: Float?) {
        val view = speedStatusView ?: return
        val actualRounded = actual?.roundToInt()
        val tolerance = posted?.let { getSpeedTolerance(it) }
        val warning = posted != null && actualRounded != null && tolerance != null && actualRounded > posted + tolerance
        view.text = buildString {
            if (warning) append("⚠  ")
            append("Posted ${posted ?: "--"}   Actual ${actualRounded ?: "--"}")
            if (posted != null && tolerance != null) append("   +$tolerance")
        }
        view.setTextColor(if (warning) 0xFFFFB74D.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listeningStatus?.text = "🎙 Route: listening" }
                override fun onBeginningOfSpeech() { listeningStatus?.text = "🎙 Route: hearing…" }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { listeningStatus?.text = "🎙 Route: processing…" }
                override fun onError(error: Int) { if (handsFreeEnabled && !suppressRestartUntilSpeechDone) startListeningSoon(700) }
                override fun onResults(results: Bundle?) {
                    val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val handled = phrases.firstNotNullOfOrNull { parseVoiceCommand(it) }
                    if (handled == null && handsFreeEnabled && !suppressRestartUntilSpeechDone) startListeningSoon(400)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun startListeningSoon(delayMs: Long) {
        mainHandler.postDelayed({
            if (!handsFreeEnabled || suppressRestartUntilSpeechDone || overlayView == null) return@postDelayed
            val recognizer = speechRecognizer ?: return@postDelayed
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CANADA.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            try { recognizer.startListening(intent) } catch (_: Exception) { startListeningSoon(1000) }
        }, delayMs)
    }

    private fun parseVoiceCommand(raw: String): String? {
        val normalized = raw.lowercase(Locale.CANADA).trim().replace(Regex("[,.!?]"), "")
        if (!normalized.startsWith("route ")) return null
        val command = normalized.removePrefix("route ").trim()
        val speed = extractSpeed(command)

        if (command.startsWith("speed") && speed != null) {
            val advance = command.contains("ahead") || command.contains("advance")
            val kind = if (advance) "speed_advance" else "speed"
            val note = if (advance) "Speed limit $speed advance sign" else "Speed limit $speed"
            performVoiceSave(kind, note, if (advance) "$speed kilometre advance sign marked" else "$speed kilometre zone marked") {
                if (!advance) TrackingState.currentPostedSpeed.value = speed
            }
            return "speed"
        }
        if (command.contains("red light camera")) { markRedLightCamera(); return "red_light_camera" }
        if (command.contains("community safety zone") && command.containsAny("start", "begin", "enter")) {
            performVoiceSave("community_safety_zone_start", "Community safety zone start", "Community safety zone start marked"); return "community_start"
        }
        if (command.contains("community safety zone") && command.containsAny("end", "exit", "leave")) {
            performVoiceSave("community_safety_zone_end", "Community safety zone end", "Community safety zone end marked"); return "community_end"
        }
        if (command.contains("senior safety zone") && command.containsAny("start", "begin", "enter")) {
            performVoiceSave("senior_safety_zone_start", "Senior safety zone start", "Senior safety zone start marked"); return "senior_start"
        }
        if (command.contains("senior safety zone") && command.containsAny("end", "exit", "leave")) {
            performVoiceSave("senior_safety_zone_end", "Senior safety zone end", "Senior safety zone end marked"); return "senior_end"
        }
        if (command == "undo" || command.contains("remove last") || command.contains("delete last")) {
            suppressRestartUntilSpeechDone = true; speechRecognizer?.cancel(); undoLatestMarker(); return "undo"
        }
        if (command.contains("remove camera") && activeVerificationMarkerId != null) { removeVerifiedCamera(); return "remove_camera" }
        if (command.contains("keep camera") && activeVerificationMarkerId != null) { keepVerifiedCamera(); return "keep_camera" }
        return null
    }

    private fun String.containsAny(vararg values: String) = values.any { contains(it) }

    private fun extractSpeed(command: String): Int? {
        Regex("\\b(20|30|40|50|60|70|80|90|100|110|120)\\b")
            .find(command)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val words = mapOf(
            "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
            "seventy" to 70, "eighty" to 80, "ninety" to 90, "one hundred" to 100,
            "one ten" to 110, "one hundred ten" to 110, "one hundred and ten" to 110,
            "one twenty" to 120, "one hundred twenty" to 120
        )
        return words.entries.firstOrNull { it.key in command }?.value
    }

    private fun performVoiceSave(kind: String, note: String, confirmation: String, afterSave: () -> Unit = {}) {
        suppressRestartUntilSpeechDone = true
        speechRecognizer?.cancel()
        saveRoadFact(kind, note) {
            afterSave()
            acknowledge(confirmation)
        }
    }

    private fun saveRoadFact(kind: String, note: String, onSaved: () -> Unit = {}) {
        val driveId = TrackingState.activeDriveId.value ?: run { acknowledge("No active drive"); return }
        scope.launch {
            val point = dao.latestPoint(driveId)
            if (point == null) {
                launch(Dispatchers.Main) { acknowledge("Waiting for GPS") }
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
            launch(Dispatchers.Main) { onSaved() }
        }
    }

    private fun markRedLightCamera() {
        suppressRestartUntilSpeechDone = true
        speechRecognizer?.cancel()
        val driveId = TrackingState.activeDriveId.value ?: run { acknowledge("No active drive"); return }
        scope.launch {
            val point = dao.latestPoint(driveId)
            if (point == null) {
                launch(Dispatchers.Main) { acknowledge("Waiting for GPS") }
                return@launch
            }
            launch(Dispatchers.Main) { showVisualAlert("Finding nearest intersection…") }
            val snap = IntersectionSnapper.findNearestIntersection(point.latitude, point.longitude)
            val latitude = snap?.latitude ?: point.latitude
            val longitude = snap?.longitude ?: point.longitude
            val note = if (snap != null) {
                "Red light camera — ${snap.intersectionName}; observed ${point.latitude},${point.longitude}"
            } else {
                "Red light camera — intersection not confirmed; observed ${point.latitude},${point.longitude}"
            }
            dao.insertMarker(
                MarkerEntity(
                    driveId = driveId,
                    timestamp = System.currentTimeMillis(),
                    latitude = latitude,
                    longitude = longitude,
                    kind = "red_light_camera",
                    note = note
                )
            )
            launch(Dispatchers.Main) {
                acknowledge(if (snap != null) "Red light camera snapped to ${snap.intersectionName}" else "Red light camera marked. Intersection not confirmed")
            }
        }
    }

    private fun undoLatestMarker() {
        val driveId = TrackingState.activeDriveId.value ?: run { acknowledge("No active drive"); return }
        scope.launch {
            val deleted = dao.deleteLatestMarker(driveId)
            launch(Dispatchers.Main) { acknowledge(if (deleted > 0) "Last marker removed" else "No marker to remove") }
        }
    }

    private fun handleDriverAlert(alert: DriverAlert) {
        showVisualAlert(alert.text)
        if (alert.kind == "red_light_camera_verify" && alert.markerId != null) {
            activeVerificationMarkerId = alert.markerId
            verificationActions?.visibility = View.VISIBLE
        }
    }

    private fun keepVerifiedCamera() {
        activeVerificationMarkerId = null
        verificationActions?.visibility = View.GONE
        acknowledge("Red light camera kept")
    }

    private fun removeVerifiedCamera() {
        val markerId = activeVerificationMarkerId ?: return
        activeVerificationMarkerId = null
        verificationActions?.visibility = View.GONE
        scope.launch {
            val deleted = dao.deleteMarker(markerId)
            launch(Dispatchers.Main) { acknowledge(if (deleted > 0) "Red light camera removed" else "Camera marker not found") }
        }
    }

    private fun acknowledge(text: String) {
        TrackingState.postDriverAlert(text)
        suppressRestartUntilSpeechDone = true
        speechRecognizer?.cancel()
        if (!ttsReady) {
            suppressRestartUntilSpeechDone = false
            if (handsFreeEnabled) startListeningSoon(500)
            return
        }
        requestTransientAudioFocus()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "overlay-${System.currentTimeMillis()}")
    }

    private fun showVisualAlert(text: String) {
        if (!visualAlertsEnabled) return
        visualAlertView?.apply { this.text = text; visibility = View.VISIBLE }
        mainHandler.removeCallbacks(clearVisualAlert)
        mainHandler.postDelayed(clearVisualAlert, 4_000L)
    }

    private fun requestTransientAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    private fun releaseAudioFocusAndResumeListening() {
        mainHandler.post {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
            suppressRestartUntilSpeechDone = false
            if (handsFreeEnabled) startListeningSoon(450)
        }
    }

    private fun exportRouteData() {
        scope.launch {
            try {
                val root = JSONObject().put("format", "routecollector-export-v1")
                    .put("exportedAt", System.currentTimeMillis())
                val drives = JSONArray()
                dao.getAllDrives().forEach { d ->
                    drives.put(JSONObject().put("id", d.id).put("startedAt", d.startedAt).put("endedAt", d.endedAt).put("title", d.title))
                }
                val points = JSONArray()
                dao.getAllPoints().forEach { p ->
                    points.put(JSONObject().put("id", p.id).put("driveId", p.driveId).put("timestamp", p.timestamp)
                        .put("latitude", p.latitude).put("longitude", p.longitude).put("accuracyMetres", p.accuracyMetres)
                        .put("speedMps", p.speedMps))
                }
                val markers = JSONArray()
                dao.getAllMarkers().forEach { m ->
                    markers.put(JSONObject().put("id", m.id).put("driveId", m.driveId).put("timestamp", m.timestamp)
                        .put("latitude", m.latitude).put("longitude", m.longitude).put("kind", m.kind).put("note", m.note))
                }
                root.put("drives", drives).put("points", points).put("markers", markers)

                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CANADA).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "routecollector-$stamp.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create export")
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString(2)) }
                    ?: error("Could not open export")
                launch(Dispatchers.Main) { acknowledge("Route data exported to Downloads") }
            } catch (_: Exception) {
                launch(Dispatchers.Main) { acknowledge("Route data export failed") }
            }
        }
    }

    override fun onDestroy() {
        handsFreeEnabled = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        listeningStatus = null
        visualAlertView = null
        speedStatusView = null
        tts?.stop()
        tts?.shutdown()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
