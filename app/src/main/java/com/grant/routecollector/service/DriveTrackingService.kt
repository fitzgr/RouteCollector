package com.grant.routecollector.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.grant.routecollector.MainActivity
import com.grant.routecollector.data.*
import com.grant.routecollector.map.RoadSpeedResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class DriveTrackingService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "routecollector.START"
        const val ACTION_STOP = "routecollector.STOP"
        const val ACTION_CLEAR_SPEED_ZONE = "routecollector.CLEAR_SPEED_ZONE"
        const val ACTION_SPEAK = "routecollector.SPEAK"
        const val EXTRA_SPEAK_TEXT = "speakText"
        const val EXTRA_DRIVE_ID = "driveId"
        private const val CHANNEL_ID = "drive_tracking"
        private const val NOTIFICATION_ID = 101
        private const val ACTIVE_ZONE_RADIUS_METRES = 70f
        private const val REDUCTION_WARNING_RADIUS_METRES = 300f
        private const val PREFS = "routecollector_overlay"
        private const val PREF_CAMERA_WARNING_METRES = "red_light_camera_warning_metres"
        private const val PREF_SPEED_TOLERANCE_PREFIX = "speed_tolerance_"
        private const val NO_DRIVING_SPEED_KPH = 5f
        private const val AUTO_BACKUP_STOPPED_MILLIS = 10 * 60 * 1000L
        private const val SAME_DIRECTION_TOLERANCE_DEGREES = 60f
        private const val REVERSE_DIRECTION_MIN_DEGREES = 120f
        private const val OSM_SPEED_RETRY_MILLIS = 30_000L
        private const val SPEED_RECOVERY_HYSTERESIS_KPH = 2f
        private const val DEER_PAIR_MAX_METRES = 20_000f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: FusedLocationProviderClient
    private lateinit var audioManager: AudioManager
    private val warningTone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    private val recoveryTone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dao by lazy { AppDatabase.get(this).dao() }
    private var driveId: Long? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val warnedReductionMarkerIds = mutableSetOf<Long>()
    private val announcedMarkerIds = mutableSetOf<Long>()
    private val warnedCameraMarkerIds = mutableSetOf<Long>()
    private val verifiedCameraMarkerIds = mutableSetOf<Long>()
    private val passiveReverseMarkerIds = mutableSetOf<Long>()
    private val previousMarkerDistances = mutableMapOf<Long, Float>()
    private val markerBearingCache = mutableMapOf<Long, Float?>()
    private var currentSpeedLimit: Int? = null
    private var overSpeedAlertActive = false
    private var stoppedSinceElapsedRealtime: Long? = null
    private var autoBackupDoneForCurrentStop = false
    private var previousLocationForBearing: Location? = null
    private var currentTravelBearing: Float? = null
    private var lastOsmSpeedAttemptElapsedRealtime = 0L
    private var osmSpeedLookupInFlight = false
    private var speedZoneManuallyCleared = false
    private var musicPausedForPrompt = false
    private val pendingSpeech = mutableListOf<String>()
    private var speechInProgress = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = driveId ?: return
            for (location in result.locations) {
                TrackingState.latestLat.value = location.latitude; TrackingState.latestLon.value = location.longitude
                val actualSpeedKph = if (location.hasSpeed()) location.speed * 3.6f else null
                TrackingState.latestSpeedKph.value = actualSpeedKph; updateTravelBearing(location, actualSpeedKph); bootstrapPostedSpeedFromOsm(location); checkOverSpeedThreshold(actualSpeedKph); checkAutomaticBackup(actualSpeedKph)
                scope.launch { dao.insertPoint(TrackPointEntity(driveId = id, timestamp = location.time, latitude = location.latitude, longitude = location.longitude, accuracyMetres = location.accuracy, speedMps = if (location.hasSpeed()) location.speed else null)); announceNearbyRoadFacts(location) }
            }
        }
    }

    override fun onCreate() { super.onCreate(); client = LocationServices.getFusedLocationProviderClient(this); audioManager = getSystemService(AUDIO_SERVICE) as AudioManager; tts = TextToSpeech(this, this); createChannel() }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) { tts?.language = Locale.CANADA; tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() { override fun onStart(utteranceId: String?) = Unit; override fun onError(utteranceId: String?) { resumeMusicAfterPrompt() }; override fun onDone(utteranceId: String?) { resumeMusicAfterPrompt() } }); ttsReady = true; val queued = pendingSpeech.toList(); pendingSpeech.clear(); queued.forEach { speak(it) } } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                driveId = intent.getLongExtra(EXTRA_DRIVE_ID, -1L).takeIf { it > 0 }
                driveId?.let {
                    warnedReductionMarkerIds.clear(); announcedMarkerIds.clear(); warnedCameraMarkerIds.clear(); verifiedCameraMarkerIds.clear(); passiveReverseMarkerIds.clear(); previousMarkerDistances.clear(); markerBearingCache.clear()
                    previousLocationForBearing = null; currentTravelBearing = null; currentSpeedLimit = null; overSpeedAlertActive = false; stoppedSinceElapsedRealtime = null; autoBackupDoneForCurrentStop = false; lastOsmSpeedAttemptElapsedRealtime = 0L; osmSpeedLookupInFlight = false; speedZoneManuallyCleared = false
                    TrackingState.currentPostedSpeed.value = null; TrackingState.currentSpeedIsCollected.value = false; TrackingState.latestSpeedKph.value = null; TrackingState.latestBearingDegrees.value = null; TrackingState.activeZoneKinds.value = emptySet(); TrackingState.currentSpeedIsCollected.value = false; TrackingState.activeDriveId.value = it
                    startForeground(NOTIFICATION_ID, buildNotification()); beginUpdates()
                }
            }
            ACTION_CLEAR_SPEED_ZONE -> clearSpeedZone()
            ACTION_SPEAK -> intent.getStringExtra(EXTRA_SPEAK_TEXT)?.takeIf { it.isNotBlank() }?.let { speak(it) }
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    private fun beginUpdates() { if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }; val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L).setMinUpdateDistanceMeters(5f).setMinUpdateIntervalMillis(1_000L).build(); client.requestLocationUpdates(request, callback, mainLooper) }
    private fun updateTravelBearing(location: Location, actualSpeedKph: Float?) { if (actualSpeedKph != null && actualSpeedKph > NO_DRIVING_SPEED_KPH) { currentTravelBearing = when { location.hasBearing() -> normalizeBearing(location.bearing); previousLocationForBearing != null -> normalizeBearing(previousLocationForBearing!!.bearingTo(location)); else -> currentTravelBearing }; TrackingState.latestBearingDegrees.value = currentTravelBearing }; previousLocationForBearing = Location(location) }
    private fun bootstrapPostedSpeedFromOsm(location: Location) { if (currentSpeedLimit != null || osmSpeedLookupInFlight || speedZoneManuallyCleared) return; val now = SystemClock.elapsedRealtime(); if (lastOsmSpeedAttemptElapsedRealtime != 0L && now - lastOsmSpeedAttemptElapsedRealtime < OSM_SPEED_RETRY_MILLIS) return; lastOsmSpeedAttemptElapsedRealtime = now; osmSpeedLookupInFlight = true; val latitude = location.latitude; val longitude = location.longitude; scope.launch { try { val result = RoadSpeedResolver.findPostedSpeed(latitude, longitude); if (result != null && currentSpeedLimit == null && driveId != null && !speedZoneManuallyCleared) { currentSpeedLimit = result.speedKph; TrackingState.currentSpeedIsCollected.value = false; TrackingState.currentPostedSpeed.value = result.speedKph; overSpeedAlertActive = false } } finally { osmSpeedLookupInFlight = false } } }
    private fun clearSpeedZone() { currentSpeedLimit = null; TrackingState.currentSpeedIsCollected.value = false; TrackingState.currentPostedSpeed.value = null; overSpeedAlertActive = false; speedZoneManuallyCleared = true; TrackingState.postDriverAlert("Posted speed cleared", kind = "zone_cleared") }

    private fun checkAutomaticBackup(actualSpeedKph: Float?) {
        val driving = actualSpeedKph != null && actualSpeedKph > NO_DRIVING_SPEED_KPH
        if (driving) { stoppedSinceElapsedRealtime = null; autoBackupDoneForCurrentStop = false; return }
        val now = SystemClock.elapsedRealtime(); val stoppedSince = stoppedSinceElapsedRealtime
        if (stoppedSince == null) { stoppedSinceElapsedRealtime = now; return }; if (autoBackupDoneForCurrentStop || now - stoppedSince < AUTO_BACKUP_STOPPED_MILLIS) return; autoBackupDoneForCurrentStop = true
        scope.launch { try { val id = driveId ?: return@launch; dao.getDrive(id)?.let { dao.updateDrive(it.copy(endedAt = System.currentTimeMillis())) }; val fileName = RouteDataExporter.exportToDownloads(this@DriveTrackingService, dao, automatic = true); TrackingState.postDriverAlert("Route backed up and drive ended after 10 minutes stopped: $fileName", kind = "auto_backup"); finishAfterAutomaticBackup() } catch (_: Exception) { autoBackupDoneForCurrentStop = false; TrackingState.postDriverAlert("Automatic route data backup failed", kind = "auto_backup_error") } }
    }
    private fun finishAfterAutomaticBackup() { client.removeLocationUpdates(callback); driveId = null; currentSpeedLimit = null; overSpeedAlertActive = false; stoppedSinceElapsedRealtime = null; previousMarkerDistances.clear(); markerBearingCache.clear(); TrackingState.activeDriveId.value = null; TrackingState.currentPostedSpeed.value = null; TrackingState.currentSpeedIsCollected.value = false; TrackingState.latestSpeedKph.value = null; TrackingState.latestBearingDegrees.value = null; TrackingState.activeZoneKinds.value = emptySet(); stopService(Intent(this, CollectorOverlayService::class.java)); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    private fun defaultTolerance(speed: Int): Int = if (speed >= 100) 9 else 8
    private fun getSpeedTolerance(speed: Int): Int = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("$PREF_SPEED_TOLERANCE_PREFIX$speed", defaultTolerance(speed))
    private fun checkOverSpeedThreshold(actualSpeedKph: Float?) {
        val posted = TrackingState.currentPostedSpeed.value ?: run { overSpeedAlertActive = false; return }
        val actual = actualSpeedKph ?: run { overSpeedAlertActive = false; return }
        val threshold = posted + getSpeedTolerance(posted)
        if (!overSpeedAlertActive && actual > threshold) {
            overSpeedAlertActive = true
            warningTone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            speak("Speed warning. ${actual.toInt()} in a $posted zone")
        } else if (overSpeedAlertActive && actual <= threshold - SPEED_RECOVERY_HYSTERESIS_KPH) {
            overSpeedAlertActive = false
            recoveryTone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            speak("Speed reduced")
        }
    }

    private suspend fun announceNearbyRoadFacts(location: Location) {
        val facts = dao.getSpokenRoadFacts(); val cameraWarningMetres = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_CAMERA_WARNING_METRES, 200).toFloat(); val deerFacts = facts.filter { it.kind == "deer_zone_enter" }
        for (fact in facts) {
            val result = FloatArray(1); Location.distanceBetween(location.latitude, location.longitude, fact.latitude, fact.longitude, result); val distance = result[0]; val previousDistance = previousMarkerDistances[fact.id]; val approaching = previousDistance == null || distance < previousDistance; previousMarkerDistances[fact.id] = distance
            when (fact.kind) { "speed", "speed_advance" -> handleDirectionalSpeedFact(fact, distance, approaching); "red_light_camera", "camera" -> handleRedLightCamera(fact, distance, approaching, cameraWarningMetres); "deer_zone_enter" -> handleDeerZoneFact(fact, deerFacts, distance, approaching); else -> handleZoneFact(fact, distance, approaching) }
        }
    }

    private suspend fun handleDirectionalSpeedFact(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        val targetSpeed = parseSpeed(fact.note) ?: return; val savedBearing = markerBearing(fact); val travelBearing = currentTravelBearing; val difference = if (savedBearing != null && travelBearing != null) bearingDifference(savedBearing, travelBearing) else null; val sameDirection = difference == null || difference <= SAME_DIRECTION_TOLERANCE_DEGREES; val reverseDirection = difference != null && difference >= REVERSE_DIRECTION_MIN_DEGREES
        if (reverseDirection) { if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in passiveReverseMarkerIds) { passiveReverseMarkerIds += fact.id; val label = if (fact.kind == "speed_advance") "advance speed sign" else "speed zone marker"; TrackingState.postDriverAlert("Opposite-direction $label: $targetSpeed km/h — informational only", kind = "directional_speed_info", markerId = fact.id) }; return }
        if (!sameDirection) return
        if (fact.kind == "speed_advance") { if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in announcedMarkerIds) { announcedMarkerIds += fact.id; speak("Reduce to $targetSpeed ahead") }; return }
        val current = currentSpeedLimit
        if (current != null && targetSpeed < current && fact.id !in warnedReductionMarkerIds && approaching && distance <= REDUCTION_WARNING_RADIUS_METRES && distance > ACTIVE_ZONE_RADIUS_METRES) { warnedReductionMarkerIds += fact.id; speak("Reduce to $targetSpeed") }
        if (fact.id !in announcedMarkerIds && approaching && distance <= ACTIVE_ZONE_RADIUS_METRES) { announcedMarkerIds += fact.id; currentSpeedLimit = targetSpeed; TrackingState.currentSpeedIsCollected.value = true; speedZoneManuallyCleared = false; TrackingState.currentPostedSpeed.value = targetSpeed; overSpeedAlertActive = false; speak("$targetSpeed kilometre zone active") }
    }

    private fun handleDeerZoneFact(fact: MarkerEntity, deerFacts: List<MarkerEntity>, distance: Float, approaching: Boolean) {
        if (fact.id in announcedMarkerIds || !approaching || distance > ACTIVE_ZONE_RADIUS_METRES) return
        val savedBearing = parseCapturedBearing(fact.note) ?: return
        val paired = deerFacts.any { other ->
            if (other.id == fact.id) return@any false; val otherBearing = parseCapturedBearing(other.note) ?: return@any false; val out = FloatArray(1); Location.distanceBetween(fact.latitude, fact.longitude, other.latitude, other.longitude, out); out[0] <= DEER_PAIR_MAX_METRES && bearingDifference(savedBearing, otherBearing) >= REVERSE_DIRECTION_MIN_DEGREES
        }
        if (!paired) return
        val travel = currentTravelBearing ?: return; val difference = bearingDifference(savedBearing, travel)
        val phrase = when { difference <= SAME_DIRECTION_TOLERANCE_DEGREES -> "Deer crossing area"; difference >= REVERSE_DIRECTION_MIN_DEGREES -> "Leaving deer area"; else -> return }
        announcedMarkerIds += fact.id; speak(phrase)
    }

    private suspend fun markerBearing(fact: MarkerEntity): Float? { if (markerBearingCache.containsKey(fact.id)) return markerBearingCache[fact.id]; val points = dao.pointsBeforeMarker(fact.driveId, fact.timestamp); val bearing = if (points.size >= 2) { val newer = points[0]; val older = points[1]; val result = FloatArray(1); Location.distanceBetween(older.latitude, older.longitude, newer.latitude, newer.longitude, result); if (result[0] >= 3f) { val from = Location("marker-history").apply { latitude = older.latitude; longitude = older.longitude }; val to = Location("marker-history").apply { latitude = newer.latitude; longitude = newer.longitude }; normalizeBearing(from.bearingTo(to)) } else null } else null; markerBearingCache[fact.id] = bearing; return bearing }
    private fun parseCapturedBearing(note: String): Float? = Regex("bearing=(-?\\d+)").find(note)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.takeIf { it >= 0f }
    private fun normalizeBearing(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun bearingDifference(a: Float, b: Float): Float { val raw = abs(normalizeBearing(a) - normalizeBearing(b)); return if (raw > 180f) 360f - raw else raw }

    private fun handleZoneFact(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        if (fact.id in announcedMarkerIds || !approaching || distance > ACTIVE_ZONE_RADIUS_METRES) return
        val phrase = when (fact.kind) { "community_safety_zone_start", "school_zone", "school_zone_start" -> { TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value + "community"; "Entering community zone" }; "community_safety_zone_end", "school_zone_end" -> { TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "community"; "Leaving community zone" }; "senior_safety_zone_start" -> { TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value + "senior"; "Entering senior zone" }; "senior_safety_zone_end" -> { TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "senior"; "Leaving senior zone" }; else -> null } ?: return
        announcedMarkerIds += fact.id; speak(phrase)
    }
    private fun handleRedLightCamera(fact: MarkerEntity, distance: Float, approaching: Boolean, warningMetres: Float) { if (approaching && distance <= warningMetres && distance > ACTIVE_ZONE_RADIUS_METRES && fact.id !in warnedCameraMarkerIds) { warnedCameraMarkerIds += fact.id; speak("Red-light-camera") }; if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in verifiedCameraMarkerIds) { verifiedCameraMarkerIds += fact.id; TrackingState.postDriverAlert(text = "Red light camera", kind = "red_light_camera_remove_available", markerId = fact.id) } }
    private fun parseSpeed(note: String): Int? = Regex("\\b(20|30|40|50|60|70|80|90|100|110|120)\\b").find(note)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun speak(text: String) {
        TrackingState.postDriverAlert(text)
        if (!ttsReady) {
            pendingSpeech += text
            return
        }
        if (speechInProgress == 0) {
            val wasMusicActive = audioManager.isMusicActive
            if (wasMusicActive) {
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                musicPausedForPrompt = true
            }
        }
        speechInProgress += 1
        mainHandler.postDelayed({
            val params = android.os.Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
            tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "routecollector-" + System.currentTimeMillis())
        }, 250L)
    }
    private fun resumeMusicAfterPrompt() {
        speechInProgress = (speechInProgress - 1).coerceAtLeast(0)
        if (speechInProgress > 0 || !musicPausedForPrompt) return
        musicPausedForPrompt = false
        mainHandler.post {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        }
    }
    private fun stopTracking() { client.removeLocationUpdates(callback); val id = driveId; if (id != null) scope.launch { dao.getDrive(id)?.let { dao.updateDrive(it.copy(endedAt = System.currentTimeMillis())) } }; driveId = null; currentSpeedLimit = null; overSpeedAlertActive = false; stoppedSinceElapsedRealtime = null; autoBackupDoneForCurrentStop = false; previousMarkerDistances.clear(); markerBearingCache.clear(); TrackingState.activeDriveId.value = null; TrackingState.currentPostedSpeed.value = null; TrackingState.currentSpeedIsCollected.value = false; TrackingState.latestSpeedKph.value = null; TrackingState.latestBearingDegrees.value = null; TrackingState.activeZoneKinds.value = emptySet(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Drive tracking", NotificationManager.IMPORTANCE_LOW)) }
    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Route Collector is recording").setContentText("GPS recording and route alerts are active").setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    override fun onDestroy() { client.removeLocationUpdates(callback); mainHandler.removeCallbacksAndMessages(null); tts?.stop(); tts?.shutdown(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
