package com.grant.routecollector.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handsFreeEnabled = true
    private var suppressRestartUntilSpeechDone = false
    private var listeningStatus: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
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
                override fun onError(utteranceId: String?) {
                    releaseAudioFocusAndResumeListening()
                }
                override fun onDone(utteranceId: String?) {
                    releaseAudioFocusAndResumeListening()
                }
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
        val help = TextView(this).apply {
            text = "Say: Speed 60 • School start • School end\nCamera • Undo"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        val handsFree = Button(this).apply { text = "Hands-free: ON" }
        val mode = Button(this).apply { text = "Mode: Highway" }
        val speedChange = Button(this).apply { text = "Mark speed change" }
        val speedGrid = GridLayout(this).apply {
            columnCount = 3
            visibility = View.GONE
        }
        val camera = Button(this).apply { text = "Mark camera" }
        val schoolStart = Button(this).apply { text = "School zone start" }
        val schoolEnd = Button(this).apply { text = "School zone end" }
        val undo = Button(this).apply { text = "Undo last marker" }
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
                            acknowledge("$speed kilometre zone marked")
                            speedGrid.visibility = View.GONE
                        }
                    }
                }
                speedGrid.addView(button)
            }
        }

        handsFree.setOnClickListener {
            handsFreeEnabled = !handsFreeEnabled
            handsFree.text = if (handsFreeEnabled) "Hands-free: ON" else "Hands-free: OFF"
            if (handsFreeEnabled) {
                listeningStatus?.text = "Hands-free: listening"
                startListeningSoon(150)
            } else {
                speechRecognizer?.cancel()
                listeningStatus?.text = "Hands-free: off"
            }
        }
        mode.setOnClickListener {
            highwayMode = !highwayMode
            mode.text = if (highwayMode) "Mode: Highway" else "Mode: Local"
            rebuildSpeedButtons()
            acknowledge(if (highwayMode) "Highway mode" else "Local road mode")
        }
        speedChange.setOnClickListener {
            if (speedGrid.visibility == View.VISIBLE) {
                speedGrid.visibility = View.GONE
            } else {
                rebuildSpeedButtons()
                speedGrid.visibility = View.VISIBLE
                acknowledge("Select new posted speed")
            }
        }
        camera.setOnClickListener {
            saveRoadFact("camera", "Camera intersection") { acknowledge("Camera marked") }
        }
        schoolStart.setOnClickListener {
            saveRoadFact("school_zone_start", "School zone start") { acknowledge("School zone start marked") }
        }
        schoolEnd.setOnClickListener {
            saveRoadFact("school_zone_end", "School zone end") { acknowledge("School zone end marked") }
        }
        undo.setOnClickListener { undoLatestMarker() }

        panel.addView(title)
        panel.addView(listeningStatus)
        panel.addView(help)
        panel.addView(handsFree)
        panel.addView(mode)
        panel.addView(speedChange)
        panel.addView(speedGrid)
        panel.addView(camera)
        panel.addView(schoolStart)
        panel.addView(schoolEnd)
        panel.addView(undo)
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
            y = 150
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

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listeningStatus?.text = "Hands-free: listening"
                }
                override fun onBeginningOfSpeech() {
                    listeningStatus?.text = "Hands-free: hearing you…"
                }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    listeningStatus?.text = "Hands-free: processing…"
                }
                override fun onError(error: Int) {
                    if (handsFreeEnabled && !suppressRestartUntilSpeechDone) startListeningSoon(700)
                }
                override fun onResults(results: Bundle?) {
                    val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val handled = phrases.firstNotNullOfOrNull { parseVoiceCommand(it) }
                    if (handled == null && handsFreeEnabled && !suppressRestartUntilSpeechDone) startListeningSoon(500)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun startListeningSoon(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!handsFreeEnabled || suppressRestartUntilSpeechDone || overlayView == null) return@postDelayed
            val recognizer = speechRecognizer ?: return@postDelayed
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CANADA.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            try {
                recognizer.startListening(intent)
            } catch (_: Exception) {
                startListeningSoon(1000)
            }
        }, delayMs)
    }

    private fun parseVoiceCommand(raw: String): String? {
        val command = raw.lowercase(Locale.CANADA).trim()
        val speed = extractSpeed(command)
        if (("speed" in command || command.startsWith("mark ")) && speed != null) {
            performVoiceSave("speed", "Speed limit $speed", "$speed kilometre zone marked")
            return "speed"
        }
        if ("school" in command && ("start" in command || "begin" in command || "enter" in command)) {
            performVoiceSave("school_zone_start", "School zone start", "School zone start marked")
            return "school_start"
        }
        if ("school" in command && ("end" in command || "exit" in command || "leave" in command)) {
            performVoiceSave("school_zone_end", "School zone end", "School zone end marked")
            return "school_end"
        }
        if (command == "camera" || "mark camera" in command || "camera intersection" in command) {
            performVoiceSave("camera", "Camera intersection", "Camera marked")
            return "camera"
        }
        if ("undo" in command || "remove last" in command || "delete last" in command) {
            suppressRestartUntilSpeechDone = true
            speechRecognizer?.cancel()
            undoLatestMarker()
            return "undo"
        }
        return null
    }

    private fun extractSpeed(command: String): Int? {
        val allowed = listOf(30, 40, 50, 60, 70, 80, 90, 100, 110)
        Regex("\\b(30|40|50|60|70|80|90|100|110)\\b").find(command)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val words = mapOf(
            "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
            "seventy" to 70, "eighty" to 80, "ninety" to 90,
            "one hundred" to 100, "hundred" to 100, "one ten" to 110,
            "one hundred ten" to 110, "one hundred and ten" to 110
        )
        return words.entries.firstOrNull { (word, value) -> value in allowed && word in command }?.value
    }

    private fun performVoiceSave(kind: String, note: String, confirmation: String) {
        suppressRestartUntilSpeechDone = true
        speechRecognizer?.cancel()
        saveRoadFact(kind, note) { acknowledge(confirmation) }
    }

    private fun saveRoadFact(kind: String, note: String, onSaved: () -> Unit = {}) {
        val driveId = TrackingState.activeDriveId.value
        if (driveId == null) {
            Toast.makeText(this, "Start a drive in Route Collector first", Toast.LENGTH_SHORT).show()
            acknowledge("No active drive")
            return
        }
        scope.launch {
            val point = dao.latestPoint(driveId)
            if (point == null) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@CollectorOverlayService, "Waiting for GPS", Toast.LENGTH_SHORT).show()
                    acknowledge("Waiting for GPS")
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

    private fun undoLatestMarker() {
        val driveId = TrackingState.activeDriveId.value
        if (driveId == null) {
            acknowledge("No active drive")
            return
        }
        scope.launch {
            val deleted = dao.deleteLatestMarker(driveId)
            launch(Dispatchers.Main) {
                acknowledge(if (deleted > 0) "Last marker removed" else "No marker to remove")
            }
        }
    }

    private fun acknowledge(text: String) {
        suppressRestartUntilSpeechDone = true
        speechRecognizer?.cancel()
        if (!ttsReady) {
            suppressRestartUntilSpeechDone = false
            if (handsFreeEnabled) startListeningSoon(500)
            return
        }
        requestTransientAudioFocus()
        val utteranceId = "overlay-${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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

    private fun hideOverlay() {
        stopSelf()
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
        tts?.stop()
        tts?.shutdown()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
