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
import android.util.Log
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
        private const val DRIVING_CONFIRMED_SPEED_KPH = 15f
        private const val AUTO_END_NO_MOVEMENT_MILLIS = 10 * 60 * 1000L
        private const val AUTO_END_MOVEMENT_METRES = 25f
        private const val AUTO_END_MAX_ACCURACY_METRES = 25f
        private const val SAME_DIRECTION_TOLERANCE_DEGREES = 50f
        private const val REVERSE_DIRECTION_MIN_DEGREES = 120f
        private const val OSM_SPEED_RETRY_MILLIS = 30_000L
        private const val OSM_SPEED_MOVING_REFRESH_MILLIS = 30_000L
        private const val SPEED_RECOVERY_HYSTERESIS_KPH = 2f
        private const val ZONE_TURN_EXIT_DEGREES = 65f
        private const val DEER_PAIR_MAX_METRES = 20_000f
        private const val PEDESTRIAN_MIN_WARNING_METRES = 120f
        private const val PEDESTRIAN_MAX_WARNING_METRES = 350f
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
    private val camerasCurrentlyInRange = mutableSetOf<Long>()
    private val passiveReverseMarkerIds = mutableSetOf<Long>()
    private val previousMarkerDistances = mutableMapOf<Long, Float>()
    private val markerBearingCache = mutableMapOf<Long, Float?>()
    private var currentSpeedLimit: Int? = null
    private var overSpeedAlertActive = false
    private var noMovementSinceElapsedRealtime: Long? = null
    private var noMovementAnchor: Location? = null
    private var drivingWasConfirmed = false
    private var previousLocationForBearing: Location? = null
    private var currentTravelBearing: Float? = null
    private var lastOsmSpeedAttemptElapsedRealtime = 0L
    private var osmSpeedLookupInFlight = false
    private var speedZoneManuallyCleared = false
    private var lastSpeedRoadBearing: Float? = null
    private var resolvedRoadName: String? = null
    private var collectedSpeedRoadName: String? = null
    private val activeZoneEntryBearings = mutableMapOf<String, Float>()
    private var musicPausedForPrompt = false
    private val pendingSpeech = mutableListOf<String>()
    private var speechInProgress = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val id = driveId ?: return
            for (location in result.locations) {
                TrackingState.latestLat.value = location.latitude; TrackingState.latestLon.value = location.longitude
                val actualSpeedKph = if (location.hasSpeed()) location.speed * 3.6f else null
                TrackingState.latestSpeedKph.value = actualSpeedKph; updateTravelBearing(location, actualSpeedKph); clearSafetyZonesAfterTurn(); clearCollectedSpeedAfterTurn(location); bootstrapPostedSpeedFromOsm(location); checkOverSpeedThreshold(actualSpeedKph); if (checkNoMovementAutoEnd(location, actualSpeedKph)) return
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
                    warnedReductionMarkerIds.clear(); announcedMarkerIds.clear(); warnedCameraMarkerIds.clear(); verifiedCameraMarkerIds.clear(); camerasCurrentlyInRange.clear(); passiveReverseMarkerIds.clear(); previousMarkerDistances.clear(); markerBearingCache.clear()
                    previousLocationForBearing = null; currentTravelBearing = null; currentSpeedLimit = null; overSpeedAlertActive = false; noMovementSinceElapsedRealtime = null; noMovementAnchor = null; drivingWasConfirmed = false; lastOsmSpeedAttemptElapsedRealtime = 0L; osmSpeedLookupInFlight = false; speedZoneManuallyCleared = false; lastSpeedRoadBearing = null; resolvedRoadName = null; collectedSpeedRoadName = null
                    TrackingState.recentZones.value = emptyList(); TrackingState.walkingAutoStopSeconds.value = null; TrackingState.currentPostedSpeed.value = null; TrackingState.currentSpeedIsCollected.value = false; TrackingState.latestSpeedKph.value = null; TrackingState.latestBearingDegrees.value = null; TrackingState.activeZoneKinds.value = emptySet(); TrackingState.currentSpeedIsCollected.value = false; TrackingState.activeDriveId.value = it
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
    private fun bootstrapPostedSpeedFromOsm(location: Location) {
        val moving = location.hasSpeed() && location.speed > 0f
        if (osmSpeedLookupInFlight || speedZoneManuallyCleared) return
        if (currentSpeedLimit != null && !moving) return
        val now = SystemClock.elapsedRealtime()
        val refreshMillis = if (moving) OSM_SPEED_MOVING_REFRESH_MILLIS else OSM_SPEED_RETRY_MILLIS
        if (lastOsmSpeedAttemptElapsedRealtime != 0L && now - lastOsmSpeedAttemptElapsedRealtime < refreshMillis) return
        lastOsmSpeedAttemptElapsedRealtime = now
        osmSpeedLookupInFlight = true
        val latitude = location.latitude
        val longitude = location.longitude
        scope.launch {
            try {
                val result = RoadSpeedResolver.findPostedSpeed(latitude, longitude)
                if (result != null && driveId != null && !speedZoneManuallyCleared) {
                    val previousRoad = resolvedRoadName
                    resolvedRoadName = result.roadName ?: previousRoad
                    val collected = TrackingState.currentSpeedIsCollected.value
                    val roadChanged = collected && collectedSpeedRoadName != null && result.roadName != null &&
                        !collectedSpeedRoadName.equals(result.roadName, ignoreCase = true)
                    if (roadChanged) {
                        currentSpeedLimit = result.speedKph
                        TrackingState.currentSpeedIsCollected.value = false
                        TrackingState.currentPostedSpeed.value = result.speedKph
                        collectedSpeedRoadName = null
                        lastSpeedRoadBearing = currentTravelBearing
                        overSpeedAlertActive = false
                        TrackingState.postDriverAlert("Road changed • ${result.roadName ?: "new road"} • ${result.speedKph} km/h", kind = "road_speed_changed")
                    } else if (!collected || currentSpeedLimit == null) {
                        currentSpeedLimit = result.speedKph
                        TrackingState.currentSpeedIsCollected.value = false
                        TrackingState.currentPostedSpeed.value = result.speedKph
                        overSpeedAlertActive = false
                    }
                }
            } finally {
                osmSpeedLookupInFlight = false
            }
        }
    }
    private fun clearSpeedZone() {
        currentSpeedLimit = null
        TrackingState.currentSpeedIsCollected.value = false
        TrackingState.currentPostedSpeed.value = null
        overSpeedAlertActive = false
        speedZoneManuallyCleared = false
        lastSpeedRoadBearing = null
        collectedSpeedRoadName = null
        lastOsmSpeedAttemptElapsedRealtime = 0L
        TrackingState.postDriverAlert("Posted speed pending", kind = "speed_pending")
        val lat = TrackingState.latestLat.value
        val lon = TrackingState.latestLon.value
        if (lat != null && lon != null) bootstrapPostedSpeedFromOsm(Location("speed-refresh").apply { latitude = lat; longitude = lon })
    }

    private fun checkNoMovementAutoEnd(location: Location, actualSpeedKph: Float?): Boolean {
        if (actualSpeedKph != null && actualSpeedKph >= DRIVING_CONFIRMED_SPEED_KPH) drivingWasConfirmed = true
        if (!drivingWasConfirmed || !location.hasAccuracy() || location.accuracy > AUTO_END_MAX_ACCURACY_METRES) {
            noMovementSinceElapsedRealtime = null
            noMovementAnchor = null
            TrackingState.walkingAutoStopSeconds.value = null
            return false
        }

        // A positive GPS speed is direct evidence that the vehicle is moving. Do not show
        // the stationary countdown while speed is non-zero; continually move the anchor
        // forward so the 10-minute timer can only begin after the vehicle actually stops.
        if (actualSpeedKph != null && actualSpeedKph > 2f) {
            noMovementAnchor = Location(location)
            noMovementSinceElapsedRealtime = null
            TrackingState.walkingAutoStopSeconds.value = null
            return false
        }

        val anchor = noMovementAnchor
        if (anchor == null) {
            noMovementAnchor = Location(location)
            noMovementSinceElapsedRealtime = SystemClock.elapsedRealtime()
            TrackingState.walkingAutoStopSeconds.value = (AUTO_END_NO_MOVEMENT_MILLIS / 1000L).toInt()
            return false
        }

        if (anchor.distanceTo(location) >= AUTO_END_MOVEMENT_METRES) {
            noMovementAnchor = Location(location)
            noMovementSinceElapsedRealtime = null
            TrackingState.walkingAutoStopSeconds.value = null
            return false
        }

        val since = noMovementSinceElapsedRealtime ?: SystemClock.elapsedRealtime().also { noMovementSinceElapsedRealtime = it }
        val elapsed = SystemClock.elapsedRealtime() - since
        val remaining = ((AUTO_END_NO_MOVEMENT_MILLIS - elapsed).coerceAtLeast(0L) + 999L) / 1000L
        TrackingState.walkingAutoStopSeconds.value = remaining.toInt()
        if (elapsed < AUTO_END_NO_MOVEMENT_MILLIS) return false

        TrackingState.walkingAutoStopSeconds.value = null
        speak("Driving mode automatically stopped. Drive saved.")
        stopTracking()
        return true
    }

    private fun defaultTolerance(speed: Int): Int = if (speed >= 100) 9 else 8
    private fun getSpeedTolerance(speed: Int): Int = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("$PREF_SPEED_TOLERANCE_PREFIX$speed", defaultTolerance(speed))
    private fun checkOverSpeedThreshold(actualSpeedKph: Float?) {
        val posted = TrackingState.currentPostedSpeed.value ?: run {
            overSpeedAlertActive = false
            TrackingState.overSpeedActive.value = false
            return
        }
        // Use the same live GPS sample that is published as "Actual" in the UI.
        val actual = actualSpeedKph ?: run {
            overSpeedAlertActive = false
            TrackingState.overSpeedActive.value = false
            return
        }
        val tolerance = getSpeedTolerance(posted)
        val threshold = posted + tolerance
        val shouldWarn = actual > threshold
        Log.d(
            "RouteCollectorSpeed",
            "posted=$posted tolerance=$tolerance threshold=$threshold actual=${"%.1f".format(Locale.US, actual)} warn=$shouldWarn active=$overSpeedAlertActive"
        )
        if (!overSpeedAlertActive && shouldWarn) {
            overSpeedAlertActive = true
            TrackingState.overSpeedActive.value = true
            warningTone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
            speak("Speed warning. ${actual.toInt()} in a $posted zone")
        } else if (overSpeedAlertActive && actual <= threshold - SPEED_RECOVERY_HYSTERESIS_KPH) {
            overSpeedAlertActive = false
            TrackingState.overSpeedActive.value = false
            recoveryTone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            speak("Good")
        }
    }

    private suspend fun announceNearbyRoadFacts(location: Location) {
        val facts = dao.getSpokenRoadFacts(); val cameraWarningMetres = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_CAMERA_WARNING_METRES, 200).toFloat(); val deerFacts = facts.filter { it.kind == "deer_zone_enter" }
        for (fact in facts) {
            val result = FloatArray(1); Location.distanceBetween(location.latitude, location.longitude, fact.latitude, fact.longitude, result); val distance = result[0]; val previousDistance = previousMarkerDistances[fact.id]; val approaching = previousDistance == null || distance < previousDistance; previousMarkerDistances[fact.id] = distance
            when (fact.kind) { "speed", "speed_advance" -> handleDirectionalSpeedFact(fact, distance, approaching); "red_light_camera", "camera" -> handleRedLightCamera(fact, distance, approaching, cameraWarningMetres); "deer_zone_enter" -> handleDeerZoneFact(fact, deerFacts, distance, approaching); "pedestrian_crossing" -> handlePedestrianCrossing(fact, distance, approaching); "passing_zone_start", "passing_zone_end" -> handlePassingZone(fact, distance, approaching); else -> handleZoneFact(fact, distance, approaching) }
        }
    }

    private suspend fun handleDirectionalSpeedFact(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        val targetSpeed = parseSpeed(fact.note) ?: return; val savedBearing = markerBearing(fact); val travelBearing = currentTravelBearing; val difference = if (savedBearing != null && travelBearing != null) bearingDifference(savedBearing, travelBearing) else null; val sameDirection = difference == null || difference <= SAME_DIRECTION_TOLERANCE_DEGREES; val reverseDirection = difference != null && difference >= REVERSE_DIRECTION_MIN_DEGREES
        if (reverseDirection) { if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in passiveReverseMarkerIds) { passiveReverseMarkerIds += fact.id; val label = if (fact.kind == "speed_advance") "advance speed sign" else "speed zone marker"; TrackingState.postDriverAlert("Opposite-direction $label: $targetSpeed km/h — informational only", kind = "directional_speed_info", markerId = fact.id) }; return }
        if (!sameDirection) return
        if (fact.kind == "speed_advance") { if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in announcedMarkerIds) { announcedMarkerIds += fact.id; speak("Reduce to $targetSpeed ahead") }; return }
        val current = currentSpeedLimit
        if (current != null && targetSpeed < current && fact.id !in warnedReductionMarkerIds && approaching && distance <= REDUCTION_WARNING_RADIUS_METRES && distance > ACTIVE_ZONE_RADIUS_METRES) { warnedReductionMarkerIds += fact.id; speak("Reduce to $targetSpeed") }
        if (fact.id !in announcedMarkerIds && approaching && distance <= ACTIVE_ZONE_RADIUS_METRES) { announcedMarkerIds += fact.id; currentSpeedLimit = targetSpeed; TrackingState.currentSpeedIsCollected.value = true; speedZoneManuallyCleared = false; lastSpeedRoadBearing = currentTravelBearing; collectedSpeedRoadName = resolvedRoadName; TrackingState.currentPostedSpeed.value = targetSpeed; overSpeedAlertActive = false; TrackingState.rememberZone(fact.id, "speed", "$targetSpeed km/h speed zone", fact.note); speak("$targetSpeed kilometre zone active") }
    }

    private fun handleDeerZoneFact(fact: MarkerEntity, deerFacts: List<MarkerEntity>, distance: Float, approaching: Boolean) {
        if (fact.id in announcedMarkerIds || !approaching || distance > ACTIVE_ZONE_RADIUS_METRES) return
        val savedBearing = parseCapturedBearing(fact.note) ?: return
        val paired = deerFacts.any { other ->
            if (other.id == fact.id) return@any false; val otherBearing = parseCapturedBearing(other.note) ?: return@any false; val out = FloatArray(1); Location.distanceBetween(fact.latitude, fact.longitude, other.latitude, other.longitude, out); out[0] <= DEER_PAIR_MAX_METRES && bearingDifference(savedBearing, otherBearing) >= REVERSE_DIRECTION_MIN_DEGREES
        }
        if (!paired) return
        val travel = currentTravelBearing ?: return; val difference = bearingDifference(savedBearing, travel)
        val phrase = when { difference <= SAME_DIRECTION_TOLERANCE_DEGREES -> if (isDeerHighRiskTime()) "Entering deer zone. Use high beams when safe." else "Entering deer zone"; difference >= REVERSE_DIRECTION_MIN_DEGREES -> "Leaving deer area"; else -> return }
        announcedMarkerIds += fact.id
        if (difference <= SAME_DIRECTION_TOLERANCE_DEGREES) { TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value + "deer"; TrackingState.rememberZone(fact.id, "deer", "Deer crossing area", fact.note) }
        else TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "deer"
        speak(phrase)
    }

    private fun clearCollectedSpeedAfterTurn(location: Location) {
        if (!TrackingState.currentSpeedIsCollected.value) return
        if (lastSpeedRoadBearing == null) { lastSpeedRoadBearing = currentTravelBearing; return }
        val entry = lastSpeedRoadBearing ?: return
        val travel = currentTravelBearing ?: return
        if (bearingDifference(entry, travel) < ZONE_TURN_EXIT_DEGREES) return
        currentSpeedLimit = null
        TrackingState.currentSpeedIsCollected.value = false
        TrackingState.currentPostedSpeed.value = null
        overSpeedAlertActive = false
        speedZoneManuallyCleared = false
        lastSpeedRoadBearing = null
        lastOsmSpeedAttemptElapsedRealtime = 0L
        TrackingState.postDriverAlert("Posted speed pending", kind = "speed_pending")
        bootstrapPostedSpeedFromOsm(location)
    }

    private fun handlePedestrianCrossing(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        val speed = TrackingState.latestSpeedKph.value ?: 50f
        val warning = (speed * 4f).coerceIn(PEDESTRIAN_MIN_WARNING_METRES, PEDESTRIAN_MAX_WARNING_METRES)
        if (approaching && distance <= warning) {
            TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value + "pedestrian"
            if (fact.id !in announcedMarkerIds) {
                announcedMarkerIds += fact.id
                speak("Pedestrian crossing ahead")
            }
        } else if (!approaching && distance > ACTIVE_ZONE_RADIUS_METRES) {
            TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "pedestrian"
        }
    }

    private suspend fun handlePassingZone(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        if (fact.id in announcedMarkerIds || !approaching || distance > ACTIVE_ZONE_RADIUS_METRES) return
        val saved = markerBearing(fact) ?: return
        val travel = currentTravelBearing ?: return
        if (bearingDifference(saved, travel) > SAME_DIRECTION_TOLERANCE_DEGREES) return
        announcedMarkerIds += fact.id
        if (fact.kind == "passing_zone_start") {
            TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value + "passing"
            TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value + "passing"
            TrackingState.rememberZone(fact.id, "passing", "Oncoming traffic risk zone", fact.note)
            speak("Beware of oncoming traffic")
        } else {
            TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "passing"
            TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "passing"
            speak("Oncoming traffic risk cleared")
        }
    }

    private fun isDeerHighRiskTime(): Boolean {
        val lat = TrackingState.latestLat.value ?: return false
        val lon = TrackingState.latestLon.value ?: return false
        val cal = java.util.Calendar.getInstance()
        val day = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val gamma = 2.0 * Math.PI / 365.0 * (day - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * kotlin.math.cos(gamma) - 0.032077 * kotlin.math.sin(gamma) - 0.014615 * kotlin.math.cos(2 * gamma) - 0.040849 * kotlin.math.sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * kotlin.math.cos(gamma) + 0.070257 * kotlin.math.sin(gamma) - 0.006758 * kotlin.math.cos(2 * gamma) + 0.000907 * kotlin.math.sin(2 * gamma) - 0.002697 * kotlin.math.cos(3 * gamma) + 0.00148 * kotlin.math.sin(3 * gamma)
        val latRad = Math.toRadians(lat)
        val zenith = Math.toRadians(90.833)
        val cosHa = ((kotlin.math.cos(zenith) / (kotlin.math.cos(latRad) * kotlin.math.cos(decl))) - kotlin.math.tan(latRad) * kotlin.math.tan(decl)).coerceIn(-1.0, 1.0)
        val haDeg = Math.toDegrees(kotlin.math.acos(cosHa))
        val tzMinutes = cal.timeZone.getOffset(cal.timeInMillis) / 60000.0
        val solarNoon = 720.0 - 4.0 * lon - eqTime + tzMinutes
        val sunrise = solarNoon - 4.0 * haDeg
        val sunset = solarNoon + 4.0 * haDeg
        val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60.0 + cal.get(java.util.Calendar.MINUTE)
        return nowMinutes >= sunset - 30.0 || nowMinutes <= sunrise + 30.0
    }

    private suspend fun markerBearing(fact: MarkerEntity): Float? { parseCapturedBearing(fact.note)?.let { return it }; if (markerBearingCache.containsKey(fact.id)) return markerBearingCache[fact.id]; val points = dao.pointsBeforeMarker(fact.driveId, fact.timestamp); val bearing = if (points.size >= 2) { val newer = points[0]; val older = points[1]; val result = FloatArray(1); Location.distanceBetween(older.latitude, older.longitude, newer.latitude, newer.longitude, result); if (result[0] >= 3f) { val from = Location("marker-history").apply { latitude = older.latitude; longitude = older.longitude }; val to = Location("marker-history").apply { latitude = newer.latitude; longitude = newer.longitude }; normalizeBearing(from.bearingTo(to)) } else null } else null; markerBearingCache[fact.id] = bearing; return bearing }
    private fun parseCapturedBearing(note: String): Float? = Regex("bearing=(-?\\d+)").find(note)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.takeIf { it >= 0f }
    private fun normalizeBearing(value: Float): Float = ((value % 360f) + 360f) % 360f
    private fun bearingDifference(a: Float, b: Float): Float { val raw = abs(normalizeBearing(a) - normalizeBearing(b)); return if (raw > 180f) 360f - raw else raw }

    private fun clearSafetyZonesAfterTurn() {
        val travel = currentTravelBearing ?: return
        val active = TrackingState.activeZoneKinds.value
        if (active.isEmpty()) return
        val exited = active.filter { zone ->
            val entry = activeZoneEntryBearings[zone] ?: return@filter false
            bearingDifference(entry, travel) >= ZONE_TURN_EXIT_DEGREES
        }.toSet()
        if (exited.isNotEmpty()) {
            TrackingState.activeZoneKinds.value = active - exited
            val id = driveId
            val lat = TrackingState.latestLat.value
            val lon = TrackingState.latestLon.value
            if (id != null && lat != null && lon != null) {
                exited.forEach { zone ->
                    val kind = if (zone == "community") "community_safety_zone_end" else "senior_safety_zone_end"
                    val label = if (zone == "community") "Community safety zone end" else "Senior safety zone end"
                    scope.launch { dao.insertMarker(MarkerEntity(driveId = id, timestamp = System.currentTimeMillis(), latitude = lat, longitude = lon, kind = kind, note = "$label; automatic turn exit")) }
                }
            }
            exited.forEach { activeZoneEntryBearings.remove(it) }
        }
    }

    private fun isSchoolActivityTime(): Boolean {
        val cal = java.util.Calendar.getInstance()
        val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
        if (day == java.util.Calendar.SATURDAY || day == java.util.Calendar.SUNDAY) return false
        val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        // Broad weekday windows cover arrival, lunch/recess activity and dismissal without
        // assuming that every community zone follows the exact same bell schedule.
        return minutes in (7 * 60 + 30)..(9 * 60 + 30) || minutes in (11 * 60)..(13 * 60 + 30) || minutes in (14 * 60)..(16 * 60 + 30)
    }

    private fun handleZoneFact(fact: MarkerEntity, distance: Float, approaching: Boolean) {
        if (fact.id in announcedMarkerIds || !approaching || distance > ACTIVE_ZONE_RADIUS_METRES) return
        val phrase = when (fact.kind) { "community_safety_zone_start", "school_zone", "school_zone_start" -> { TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value + "community"; TrackingState.rememberZone(fact.id, "community", "Community safety zone", fact.note); currentTravelBearing?.let { activeZoneEntryBearings["community"] = it }; if (isSchoolActivityTime()) "Entering community zone. School hours. Watch for children." else "Entering community zone" }; "community_safety_zone_end", "school_zone_end" -> { TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "community"; activeZoneEntryBearings.remove("community"); "Leaving community zone" }; "senior_safety_zone_start" -> { TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value + "senior"; TrackingState.rememberZone(fact.id, "senior", "Senior safety zone", fact.note); currentTravelBearing?.let { activeZoneEntryBearings["senior"] = it }; "Entering senior zone" }; "senior_safety_zone_end" -> { TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "senior"; activeZoneEntryBearings.remove("senior"); "Leaving senior zone" }; else -> null } ?: return
        announcedMarkerIds += fact.id; speak(phrase)
    }
    private suspend fun handleRedLightCamera(fact: MarkerEntity, distance: Float, approaching: Boolean, warningMetres: Float) {
        val savedBearing = markerBearing(fact)
        val travel = currentTravelBearing
        if (savedBearing != null && travel != null && bearingDifference(savedBearing, travel) > SAME_DIRECTION_TOLERANCE_DEGREES) {
            camerasCurrentlyInRange -= fact.id
            TrackingState.activeRoadAlerts.value = if (camerasCurrentlyInRange.isNotEmpty()) TrackingState.activeRoadAlerts.value + "camera" else TrackingState.activeRoadAlerts.value - "camera"
            return
        }
        if (distance <= warningMetres) camerasCurrentlyInRange += fact.id else camerasCurrentlyInRange -= fact.id
        TrackingState.activeRoadAlerts.value = if (camerasCurrentlyInRange.isNotEmpty()) TrackingState.activeRoadAlerts.value + "camera" else TrackingState.activeRoadAlerts.value - "camera"
        if (approaching && distance <= warningMetres && distance > ACTIVE_ZONE_RADIUS_METRES && fact.id !in warnedCameraMarkerIds) { warnedCameraMarkerIds += fact.id; speak("Red-light-camera") }
        if (approaching && distance <= ACTIVE_ZONE_RADIUS_METRES && fact.id !in verifiedCameraMarkerIds) { verifiedCameraMarkerIds += fact.id; TrackingState.postDriverAlert(text = "Red light camera", kind = "red_light_camera_remove_available", markerId = fact.id) }
    }
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
    private fun stopTracking() { client.removeLocationUpdates(callback); val id = driveId; if (id != null) scope.launch { dao.getDrive(id)?.let { dao.updateDrive(it.copy(endedAt = System.currentTimeMillis())) }; runCatching { RouteDataExporter.exportToDownloads(this@DriveTrackingService, dao, automatic = true) }.onFailure { Log.e("RouteCollectorBackup", "Automatic route backup failed", it) } }; driveId = null; currentSpeedLimit = null; overSpeedAlertActive = false; noMovementSinceElapsedRealtime = null; noMovementAnchor = null; previousMarkerDistances.clear(); markerBearingCache.clear(); TrackingState.activeDriveId.value = null; TrackingState.currentPostedSpeed.value = null; TrackingState.currentSpeedIsCollected.value = false; TrackingState.latestSpeedKph.value = null; TrackingState.latestBearingDegrees.value = null; TrackingState.activeZoneKinds.value = emptySet(); TrackingState.activeRoadAlerts.value = emptySet(); TrackingState.overSpeedActive.value = false; TrackingState.walkingAutoStopSeconds.value = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Drive tracking", NotificationManager.IMPORTANCE_LOW)) }
    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Route Collector is recording").setContentText("GPS recording and route alerts are active").setOngoing(true).setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    override fun onDestroy() { client.removeLocationUpdates(callback); mainHandler.removeCallbacksAndMessages(null); tts?.stop(); tts?.shutdown(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
