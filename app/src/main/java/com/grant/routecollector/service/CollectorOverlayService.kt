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
import android.widget.Button
import android.widget.LinearLayout
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CollectorOverlayService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_SHOW = "routecollector.OVERLAY_SHOW"
        const val ACTION_HIDE = "routecollector.OVERLAY_HIDE"
        private const val PREFS = "routecollector_overlay"
        private const val PREF_VISUAL_ALERTS = "visual_alerts_enabled"
        private const val PREF_CAMERA_WARNING_METRES = "red_light_camera_warning_metres"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.get(this).dao() }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handsFreeEnabled = true
    private var suppressRestartUntilSpeechDone = false
    private var listeningStatus: TextView? = null
    private var visualAlertView: TextView? = null
    private var visualAlertsEnabled = true
    private var settingsPanel: LinearLayout? = null
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
            setBackgroundColor(0xE6202124.toInt())
        }
        val title = TextView(this).apply {
            text = "Route Collector"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
        }
        listeningStatus = TextView(this).apply {
            text = "Hands-free: starting…"
            setTextColor(0xFFB8E986.toInt())
            textSize = 13f
        }
        val grammar = TextView(this).apply {
            text = "YOU SAY\n<command> ::= Route <action>\nExample: “Route speed 60”\n<action> ::= Speed <limit> | Red light camera | Community safety zone <start|end> | Senior safety zone <start|end> | Undo\n\nROUTE COLLECTOR SAYS\n<alert> ::= Speed reduction to <limit> | <limit> zone active | Red light camera ahead | Entering/leaving safety zone"
            setTextColor(0xFFD8D8D8.toInt())
            textSize = 12f
        }
        visualAlertView = TextView(this).apply {
            visibility = View.GONE
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF37474F.toInt())
            setPadding(14, 10, 14, 10)
            textSize = 14f
        }
        verificationActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val keepCamera = Button(this).apply {
            text = "Keep"
            setOnClickListener { keepVerifiedCamera() }
        }
        val removeCamera = Button(this).apply {
            text = "Remove"
            setOnClickListener { removeVerifiedCamera() }
        }
        verificationActions?.addView(keepCamera)
        verificationActions?.addView(removeCamera)

        val handsFree = Button(this).apply { text = "Hands-free: ON" }
        val redLightCamera = Button(this).apply { text = "Mark red light camera" }
        val communityStart = Button(this).apply { text = "Community safety zone start" }
        val communityEnd = Button(this).apply { text = "Community safety zone end" }
        val seniorStart = Button(this).apply { text = "Senior safety zone start" }
        val seniorEnd = Button(this).apply { text = "Senior safety zone end" }
        val undo = Button(this).apply { text = "Undo last marker" }
        val settings = Button(this).apply { text = "⚙ Settings" }
        val hide = Button(this).apply { text = "Hide"; setOnClickListener { stopSelf() } }

        settingsPanel = buildSettingsPanel()

        handsFree.setOnClickListener {
            handsFreeEnabled = !handsFreeEnabled
            handsFree.text = if (handsFreeEnabled) "Hands-free: ON" else "Hands-free: OFF"
            if (handsFreeEnabled) {
                listeningStatus?.text = "Hands-free: listening for ‘Route …’"
                startListeningSoon(150)
            } else {
                speechRecognizer?.cancel()
                listeningStatus?.text = "Hands-free: off"
            }
        }
        redLightCamera.setOnClickListener { markRedLightCamera() }
        communityStart.setOnClickListener { saveRoadFact("community_safety_zone_start", "Community safety zone start") { acknowledge("Community safety zone start marked") } }
        communityEnd.setOnClickListener { saveRoadFact("community_safety_zone_end", "Community safety zone end") { acknowledge("Community safety zone end marked") } }
        seniorStart.setOnClickListener { saveRoadFact("senior_safety_zone_start", "Senior safety zone start") { acknowledge("Senior safety zone start marked") } }
        seniorEnd.setOnClickListener { saveRoadFact("senior_safety_zone_end", "Senior safety zone end") { acknowledge("Senior safety zone end marked") } }
        undo.setOnClickListener { undoLatestMarker() }
        settings.setOnClickListener {
            settingsPanel?.visibility = if (settingsPanel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        panel.addView(title)
        panel.addView(listeningStatus)
        panel.addView(grammar)
        panel.addView(visualAlertView)
        panel.addView(verificationActions)
        panel.addView(handsFree)
        panel.addView(redLightCamera)
        panel.addView(communityStart)
        panel.addView(communityEnd)
        panel.addView(seniorStart)
        panel.addView(seniorEnd)
        panel.addView(undo)
        panel.addView(settings)
        panel.addView(settingsPanel)
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
            y = 120
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
        if (handsFreeEnabled) startListeningSoon(300)
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
                } else {
                    showVisualAlert("Visual alerts enabled")
                }
            }
        }
        val distanceLabel = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }
        fun refreshDistance() {
            distanceLabel.text = "Red light camera warning: ${prefs.getInt(PREF_CAMERA_WARNING_METRES, 200)} m"
        }
        refreshDistance()
        val distanceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val less = Button(this).apply {
            text = "−50 m"
            setOnClickListener {
                val value = (prefs.getInt(PREF_CAMERA_WARNING_METRES, 200) - 50).coerceAtLeast(100)
                prefs.edit().putInt(PREF_CAMERA_WARNING_METRES, value).apply()
                refreshDistance()
            }
        }
        val more = Button(this).apply {
            text = "+50 m"
            setOnClickListener {
                val value = (prefs.getInt(PREF_CAMERA_WARNING_METRES, 200) + 50).coerceAtMost(500)
                prefs.edit().putInt(PREF_CAMERA_WARNING_METRES, value).apply()
                refreshDistance()
            }
        }
        distanceRow.addView(less)
        distanceRow.addView(more)
        val export = Button(this).apply {
            text = "Export route data"
            setOnClickListener { exportRouteData() }
        }
        panel.addView(visualAlerts)
        panel.addView(distanceLabel)
        panel.addView(distanceRow)
        panel.addView(export)
        return panel
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listeningStatus?.text = "Hands-free: listening for ‘Route …’" }
                override fun onBeginningOfSpeech() { listeningStatus?.text = "Hands-free: hearing you…" }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { listeningStatus?.text = "Hands-free: processing…" }
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
            performVoiceSave("speed", "Speed limit $speed", "$speed kilometre zone marked")
            return "speed"
        }
        if (command.contains("red light camera")) {
            markRedLightCamera()
            return "red_light_camera"
        }
        if (command.contains("community safety zone") && command.containsAny("start", "begin", "enter")) {
            performVoiceSave("community_safety_zone_start", "Community safety zone start", "Community safety zone start marked")
            return "community_start"
        }
        if (command.contains("community safety zone") && command.containsAny("end", "exit", "leave")) {
            performVoiceSave("community_safety_zone_end", "Community safety zone end", "Community safety zone end marked")
            return "community_end"
        }
        if (command.contains("senior safety zone") && command.containsAny("start", "begin", "enter")) {
            performVoiceSave("senior_safety_zone_start", "Senior safety zone start", "Senior safety zone start marked")
            return "senior_start"
        }
        if (command.contains("senior safety zone") && command.containsAny("end", "exit", "leave")) {
            performVoiceSave("senior_safety_zone_end", "Senior safety zone end", "Senior safety zone end marked")
            return "senior_end"
        }
        if (command == "undo" || command.contains("remove last") || command.contains("delete last")) {
            suppressRestartUntilSpeechDone = true
            speechRecognizer?.cancel()
            undoLatestMarker()
            return "undo"
        }
        if (command.contains("remove camera") && activeVerificationMarkerId != null) {
            removeVerifiedCamera()
            return "remove_camera"
        }
        if (command.contains("keep camera") && activeVerificationMarkerId != null) {
            keepVerifiedCamera()
            return "keep_camera"
        }
        return null
    }

    private fun String.containsAny(vararg values: String) = values.any { contains(it) }

    private fun extractSpeed(command: String): Int? {
        Regex("\\b(30|40|50|60|70|80|90|100|110)\\b").find(command)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val words = mapOf(
            "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
            "seventy" to 70, "eighty" to 80, "ninety" to 90,
            "one hundred" to 100, "one ten" to 110,
            "one hundred ten" to 110, "one hundred and ten" to 110
        )
        return words.entries.firstOrNull { (word, _) -> word in command }?.value
    }

    private fun performVoiceSave(kind: String, note: String, confirmation: String) {
        suppressRestartUntilSpeechDone = true
        speechRecognizer?.cancel()
        saveRoadFact(kind, note) { acknowledge(confirmation) }
    }

    private fun markRedLightCamera() {
        suppressRestartUntilSpeechDone = true
        speechRecognizer?.cancel()
        val driveId = TrackingState.activeDriveId.value ?: run {
            acknowledge("No active drive")
            return
        }
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
                if (snap != null) acknowledge("Red light camera snapped to ${snap.intersectionName}")
                else acknowledge("Red light camera marked. Intersection not confirmed")
            }
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
            dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude, kind = kind, note = note))
            launch(Dispatchers.Main) { onSaved() }
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

    private fun exportRouteData() {
        scope.launch {
            try {
                val root = JSONObject()
                val drives = JSONArray()
                dao.getAllDrives().forEach { d ->
                    drives.put(JSONObject().apply {
                        put("id", d.id); put("startedAt", d.startedAt); put("endedAt", d.endedAt); put("title", d.title)
                    })
                }
                val points = JSONArray()
                dao.getAllPoints().forEach { p ->
                    points.put(JSONObject().apply {
                        put("id", p.id); put("driveId", p.driveId); put("timestamp", p.timestamp); put("latitude", p.latitude); put("longitude", p.longitude); put("accuracyMetres", p.accuracyMetres); put("speedMps", p.speedMps)
                    })
                }
                val markers = JSONArray()
                dao.getAllMarkers().forEach { m ->
                    markers.put(JSONObject().apply {
                        put("id", m.id); put("driveId", m.driveId); put("timestamp", m.timestamp); put("latitude", m.latitude); put("longitude", m.longitude); put("kind", m.kind); put("note", m.note)
                    })
                }
                root.put("format", "routecollector-export-v1")
                root.put("exportedAt", System.currentTimeMillis())
                root.put("drives", drives)
                root.put("trackPoints", points)
                root.put("markers", markers)

                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CANADA).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "RouteCollector-$timestamp.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Unable to create export file")
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString(2)) }
                    ?: error("Unable to write export file")
                launch(Dispatchers.Main) { acknowledge("Route data exported to Downloads") }
            } catch (_: Exception) {
                launch(Dispatchers.Main) { acknowledge("Route data export failed") }
            }
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
        visualAlertView?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
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

    private fun hideOverlay() = stopSelf()

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
        verificationActions = null
        settingsPanel = null
        tts?.stop()
        tts?.shutdown()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
