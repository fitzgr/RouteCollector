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

class DriveTrackingService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "routecollector.START"
        const val ACTION_STOP = "routecollector.STOP"
        const val EXTRA_DRIVE_ID = "driveId"
        private const val CHANNEL_ID = "drive_tracking"
        private const val NOTIFICATION_ID = 101
        private const val ACTIVE_ZONE_RADIUS_METRES = 70f
        private const val REDUCTION_WARNING_RADIUS_METRES = 300f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: FusedLocationProviderClient
    private val dao by lazy { AppDatabase.get(this).dao() }
    private var driveId: Long? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // A marker can have two distinct announcements: an advance reduction warning and its active-zone announcement.
    private val warnedReductionMarkerIds = mutableSetOf<Long>()
    private val announcedMarkerIds = mutableSetOf<Long>()
    private val previousMarkerDistances = mutableMapOf<Long, Float>()
    private var currentSpeedLimit: Int? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = driveId ?: return
            for (location in result.locations) {
                TrackingState.latestLat.value = location.latitude
                TrackingState.latestLon.value = location.longitude
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
                    previousMarkerDistances.clear()
                    currentSpeedLimit = null
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
            stopSelf(); return
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateDistanceMeters(5f)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)
    }

    private suspend fun announceNearbyRoadFacts(location: Location) {
        val facts = dao.getSpokenRoadFacts()
        for (fact in facts) {
            val distanceResult = FloatArray(1)
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                fact.latitude,
                fact.longitude,
                distanceResult
            )
            val distance = distanceResult[0]
            val previousDistance = previousMarkerDistances[fact.id]
            val approaching = previousDistance == null || distance < previousDistance
            previousMarkerDistances[fact.id] = distance

            if (fact.kind == "speed") {
                handleSpeedFact(fact, distance, approaching)
                continue
            }

            if (fact.id in announcedMarkerIds || !approaching || distance > ACTIVE_ZONE_RADIUS_METRES) continue
            val phrase = when (fact.kind) {
                "school_zone", "school_zone_start" -> "Entering school zone"
                "school_zone_end" -> "Leaving school zone"
                else -> null
            }
            if (phrase != null) {
                announcedMarkerIds += fact.id
                speak(phrase)
            }
        }
    }

    private fun handleSpeedFact(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        val targetSpeed = parseSpeed(fact.note) ?: return
        val current = currentSpeedLimit

        // Advance warnings are intentionally asymmetric: warn before a reduction, but not before an increase.
        // We also require the marker to be getting closer to reduce warnings from nearby/behind markers.
        if (
            current != null &&
            targetSpeed < current &&
            fact.id !in warnedReductionMarkerIds &&
            approaching &&
            distance <= REDUCTION_WARNING_RADIUS_METRES &&
            distance > ACTIVE_ZONE_RADIUS_METRES
        ) {
            warnedReductionMarkerIds += fact.id
            val roundedDistance = ((distance / 50f).toInt().coerceAtLeast(1) * 50)
            speak("Speed reduction to $targetSpeed in about $roundedDistance metres")
        }

        // At the collected boundary, announce both increases and reductions and make the new zone current.
        if (
            fact.id !in announcedMarkerIds &&
            approaching &&
            distance <= ACTIVE_ZONE_RADIUS_METRES
        ) {
            announcedMarkerIds += fact.id
            currentSpeedLimit = targetSpeed
            speak("$targetSpeed kilometre zone active")
        }
    }

    private fun parseSpeed(note: String): Int? =
        Regex("\\b(20|30|40|50|60|70|80|90|100|110|120)\\b")
            .find(note)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

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
                val drive = dao.getDrive(id)
                if (drive != null) dao.updateDrive(drive.copy(endedAt = System.currentTimeMillis()))
            }
        }
        driveId = null
        currentSpeedLimit = null
        previousMarkerDistances.clear()
        TrackingState.activeDriveId.value = null
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
