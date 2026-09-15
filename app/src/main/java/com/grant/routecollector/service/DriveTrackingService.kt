package com.grant.routecollector.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.grant.routecollector.MainActivity
import com.grant.routecollector.data.*
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: FusedLocationProviderClient
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

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = driveId ?: return
            for (location in result.locations) {
                TrackingState.latestLat.value = location.latitude
                TrackingState.latestLon.value = location.longitude
                val actualSpeedKph = if (location.hasSpeed()) location.speed * 3.6f else null
                TrackingState.latestSpeedKph.value = actualSpeedKph
                updateTravelBearing(location, actualSpeedKph)
                checkOverSpeedThreshold(actualSpeedKph)
                checkAutomaticBackup(actualSpeedKph)
                scope.launch {
                    dao.insertPoint(
                        TrackPointEntity(
                            driveId = id,
                            timestamp = location.time,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMetres = location.accuracy,
                            speedMps = if (location.hasSpeed()) location.speed else null
                        )
                    )
                    announceNearbyRoadFacts(location)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
        createChannel()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.CANADA
            ttsReady = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                driveId = intent.getLongExtra(EXTRA_DRIVE_ID, -1L).takeIf { it > 0 }
                driveId?.let {
                    warnedReductionMarkerIds.clear()
                    announcedMarkerIds.clear()
                    warnedCameraMarkerIds.clear()
                    verifiedCameraMarkerIds.clear()
                    passiveReverseMarkerIds.clear()
                    previousMarkerDistances.clear()
                    markerBearingCache.clear()
                    previousLocationForBearing = null
                    currentTravelBearing = null
                    currentSpeedLimit = null
                    overSpeedAlertActive = false
                    stoppedSinceElapsedRealtime = null
                    autoBackupDoneForCurrentStop = false
                    TrackingState.currentPostedSpeed.value = null
                    TrackingState.latestSpeedKph.value = null
                    TrackingState.latestBearingDegrees.value = null
                    TrackingState.activeDriveId.value = it
                    startForeground(NOTIFICATION_ID, buildNotification())
                    beginUpdates()
                }
            }
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    private fun beginUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateDistanceMeters(5f)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun updateTravelBearing(location: Location, actualSpeedKph: Float?) {
        if (actualSpeedKph != null && actualSpeedKph > NO_DRIVING_SPEED_KPH) {
            currentTravelBearing = when {
                location.hasBearing() -> normalizeBearing(location.bearing)
                previousLocationForBearing != null -> normalizeBearing(previousLocationForBearing!!.bearingTo(location))
                else -> currentTravelBearing
            }
            TrackingState.latestBearingDegrees.value = currentTravelBearing
        }
        previousLocationForBearing = Location(location)
    }

    private fun checkAutomaticBackup(actualSpeedKph: Float?) {
        val driving = actualSpeedKph != null && actualSpeedKph > NO_DRIVING_SPEED_KPH
        if (driving) {
            stoppedSinceElapsedRealtime = null
            autoBackupDoneForCurrentStop = false
            return
        }

        val now = SystemClock.elapsedRealtime()
        val stoppedSince = stoppedSinceElapsedRealtime
        if (stoppedSince == null) {
            stoppedSinceElapsedRealtime = now
            return
        }
        if (autoBackupDoneForCurrentStop || now - stoppedSince < AUTO_BACKUP_STOPPED_MILLIS) return

        autoBackupDoneForCurrentStop = true
        scope.launch {
            try {
                val id = driveId ?: return@launch
                dao.getDrive(id)?.let { dao.updateDrive(it.copy(endedAt = System.currentTimeMillis())) }
                val fileName = RouteDataExporter.exportToDownloads(this@DriveTrackingService, dao, automatic = true)
                TrackingState.postDriverAlert("Route backed up and drive ended after 10 minutes stopped: $fileName", kind = "auto_backup")
                finishAfterAutomaticBackup()
            } catch (_: Exception) {
                autoBackupDoneForCurrentStop = false
                TrackingState.postDriverAlert("Automatic route data backup failed", kind = "auto_backup_error")
            }
        }
    }

    private fun finishAfterAutomaticBackup() {
        client.removeLocationUpdates(callback)
        driveId = null
        currentSpeedLimit = null
        overSpeedAlertActive = false
        stoppedSinceElapsedRealtime = null
        previousMarkerDistances.clear()
        markerBearingCache.clear()
        TrackingState.activeDriveId.value = null
        TrackingState.currentPostedSpeed.value = null
        TrackingState.latestSpeedKph.value = null
        TrackingState.latestBearingDegrees.value = null
        stopService(Intent(this, CollectorOverlayService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun defaultTolerance(speed: Int): Int = if (speed >= 100) 9 else 8

    private fun getSpeedTolerance(speed: Int): Int =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt("$PREF_SPEED_TOLERANCE_PREFIX$speed", defaultTolerance(speed))

    private fun checkOverSpeedThreshold(actualSpeedKph: Float?) {
        val posted = TrackingState.currentPostedSpeed.value ?: run {
            overSpeedAlertActive = false
            return
        }
        val actual = actualSpeedKph ?: run {
            overSpeedAlertActive = false
            return
        }
        val threshold = posted + getSpeedTolerance(posted)
        val overThreshold = actual > threshold

        if (overThreshold && !overSpeedAlertActive) {
            overSpeedAlertActive = true
            speak("Speed threshold exceeded")
        } else if (!overThreshold) {
            overSpeedAlertActive = false
        }
    }

    private suspend fun announceNearbyRoadFacts(location: Location) {
        val facts = dao.getSpokenRoadFacts()
        val cameraWarningMetres = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(PREF_CAMERA_WARNING_METRES, 200)
            .toFloat()

        for (fact in facts) {
            val distanceResult = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, fact.latitude, fact.longitude, distanceResult)
            val distance = distanceResult[0]
            val previousDistance = previousMarkerDistances[fact.id]
            val approaching = previousDistance == null || distance < previousDistance
            previousMarkerDistances[fact.id] = distance

            when (fact.kind) {
                "speed", "speed_advance" -> handleDirectionalSpeedFact(fact, distance, approaching)
                "red_light_camera", "camera" -> handleRedLightCamera(fact, distance, approaching, cameraWarningMetres)
                else -> handleZoneFact(fact, distance, approaching)
            }
        }
    }

    private suspend fun handleDirectionalSpeedFact(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        val targetSpeed = parseSpeed(fact.note) ?: return
        val savedBearing = markerBearing(fact)
        val travelBearing = currentTravelBearing
        val difference = if (savedBearing != null && travelBearing != null) bearingDifference(savedBearing, travelBearing) else null

        // Existing markers without enough historical track data retain their legacy behaviour.
        val sameDirection = difference == null || difference <= SAME_DIRECTION_TOLERANCE_DEGREES
        val reverseDirection = difference != null && difference >= REVERSE_DIRECTION_MIN_DEGREES

        if (reverseDirection) {
            if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in passiveReverseMarkerIds) {
                passiveReverseMarkerIds += fact.id
                val label = if (fact.kind == "speed_advance") "advance speed sign" else "speed zone marker"
                TrackingState.postDriverAlert(
                    "Opposite-direction $label: $targetSpeed km/h — informational only",
                    kind = "directional_speed_info",
                    markerId = fact.id
                )
            }
            return
        }
        if (!sameDirection) return

        if (fact.kind == "speed_advance") {
            if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in announcedMarkerIds) {
                announcedMarkerIds += fact.id
                speak("Speed reduction to $targetSpeed ahead")
            }
            return
        }

        val current = currentSpeedLimit
        if (
            current != null && targetSpeed < current && fact.id !in warnedReductionMarkerIds &&
            approaching && distance <= REDUCTION_WARNING_RADIUS_METRES && distance > ACTIVE_ZONE_RADIUS_METRES
        ) {
            warnedReductionMarkerIds += fact.id
            speak("Speed reduction to $targetSpeed")
        }
        if (fact.id !in announcedMarkerIds && approaching && distance <= ACTIVE_ZONE_RADIUS_METRES) {
            announcedMarkerIds += fact.id
            currentSpeedLimit = targetSpeed
            TrackingState.currentPostedSpeed.value = targetSpeed
            overSpeedAlertActive = false
            speak("$targetSpeed kilometre zone active")
        }
    }

    private suspend fun markerBearing(fact: MarkerEntity): Float? {
        if (markerBearingCache.containsKey(fact.id)) return markerBearingCache[fact.id]
        val points = dao.pointsBeforeMarker(fact.driveId, fact.timestamp)
        val bearing = if (points.size >= 2) {
            val newer = points[0]
            val older = points[1]
            val result = FloatArray(1)
            Location.distanceBetween(older.latitude, older.longitude, newer.latitude, newer.longitude, result)
            if (result[0] >= 3f) {
                val from = Location("marker-history").apply { latitude = older.latitude; longitude = older.longitude }
                val to = Location("marker-history").apply { latitude = newer.latitude; longitude = newer.longitude }
                normalizeBearing(from.bearingTo(to))
            } else null
        } else null
        markerBearingCache[fact.id] = bearing
        return bearing
    }

    private fun normalizeBearing(value: Float): Float = ((value % 360f) + 360f) % 360f

    private fun bearingDifference(a: Float, b: Float): Float {
        val raw = abs(normalizeBearing(a) - normalizeBearing(b))
        return if (raw > 180f) 360f - raw else raw
    }

    private fun handleZoneFact(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        if (fact.id in announcedMarkerIds || !approaching || distance > ACTIVE_ZONE_RADIUS_METRES) return
        val phrase = when (fact.kind) {
            "community_safety_zone_start", "school_zone", "school_zone_start" -> "Entering community safety zone"
            "community_safety_zone_end", "school_zone_end" -> "Leaving community safety zone"
            "senior_safety_zone_start" -> "Entering senior safety zone"
            "senior_safety_zone_end" -> "Leaving senior safety zone"
            else -> null
        } ?: return
        announcedMarkerIds += fact.id
        speak(phrase)
    }

    private fun handleRedLightCamera(fact: MarkerEntity, distance: Float, approaching: Boolean, warningMetres: Float) {
        if (approaching && distance <= warningMetres && distance > ACTIVE_ZONE_RADIUS_METRES && fact.id !in warnedCameraMarkerIds) {
            warnedCameraMarkerIds += fact.id
            speak("Red light camera ahead")
        }
        if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in verifiedCameraMarkerIds) {
            verifiedCameraMarkerIds += fact.id
            TrackingState.postDriverAlert(
                text = "Red light camera here — keep or remove?",
                kind = "red_light_camera_verify",
                markerId = fact.id
            )
        }
    }

    private fun parseSpeed(note: String): Int? =
        Regex("\\b(20|30|40|50|60|70|80|90|100|110|120)\\b")
            .find(note)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun speak(text: String) {
        TrackingState.postDriverAlert(text)
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "routecollector-${System.currentTimeMillis()}")
    }

    private fun stopTracking() {
        client.removeLocationUpdates(callback)
        val id = driveId
        if (id != null) {
            scope.launch {
                dao.getDrive(id)?.let { dao.updateDrive(it.copy(endedAt = System.currentTimeMillis())) }
            }
        }
        driveId = null
        currentSpeedLimit = null
        overSpeedAlertActive = false
        stoppedSinceElapsedRealtime = null
        autoBackupDoneForCurrentStop = false
        previousMarkerDistances.clear()
        markerBearingCache.clear()
        TrackingState.activeDriveId.value = null
        TrackingState.currentPostedSpeed.value = null
        TrackingState.latestSpeedKph.value = null
        TrackingState.latestBearingDegrees.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Drive tracking", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Route Collector is recording")
        .setContentText("GPS recording and route alerts are active")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        tts?.stop()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
