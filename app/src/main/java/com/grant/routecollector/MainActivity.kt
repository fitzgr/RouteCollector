package com.grant.routecollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import com.grant.routecollector.map.IntersectionSnapper
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grant.routecollector.data.*
import com.grant.routecollector.service.CollectorOverlayService
import com.grant.routecollector.service.DriveTrackingService
import com.grant.routecollector.service.RouteMarkerMapOverlayService
import com.grant.routecollector.ui.RouteMap
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RouteCollectorScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteCollectorScreen() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).dao() }
    val scope = rememberCoroutineScope()
    val activeDriveId by TrackingState.activeDriveId.collectAsStateWithLifecycle()
    val drives by dao.observeDrives().collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistory by remember { mutableStateOf(false) }
    var selectedHistoryDriveId by remember { mutableStateOf<Long?>(null) }
    var cameraCaptureMessage by remember { mutableStateOf<String?>(null) }

    val effectiveDriveId = activeDriveId ?: selectedHistoryDriveId ?: drives.firstOrNull()?.id
    val pointsFlow = remember(effectiveDriveId) { effectiveDriveId?.let { dao.observePoints(it) } ?: flowOf(emptyList()) }
    val markersFlow = remember(effectiveDriveId) { effectiveDriveId?.let { dao.observeMarkers(it) } ?: flowOf(emptyList()) }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val markers by markersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val postedSpeed by TrackingState.currentPostedSpeed.collectAsStateWithLifecycle()
    val actualSpeed by TrackingState.latestSpeedKph.collectAsStateWithLifecycle()
    val collectedSpeed by TrackingState.currentSpeedIsCollected.collectAsStateWithLifecycle()
    var showSpeedMarker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("routecollector_overlay", android.content.Context.MODE_PRIVATE) }
    var visualAlerts by remember { mutableStateOf(prefs.getBoolean("visual_alerts_enabled", true)) }
    var cameraWarning by remember { mutableIntStateOf(prefs.getInt("red_light_camera_warning_metres", 200)) }
    var toleranceSpeed by remember { mutableIntStateOf(60) }
    var toleranceValue by remember { mutableIntStateOf(prefs.getInt("speed_tolerance_60", 8)) }
    var markerSpeed by remember { mutableStateOf(60) }
    var pendingSpeedChosen by remember { mutableStateOf(false) }
    var markerType by remember { mutableStateOf("Zone begins") }
    val activeZones by TrackingState.activeZoneKinds.collectAsStateWithLifecycle()
    val activeRoadAlerts by TrackingState.activeRoadAlerts.collectAsStateWithLifecycle()
    val overSpeedActive by TrackingState.overSpeedActive.collectAsStateWithLifecycle()
    val walkingCountdown by TrackingState.walkingAutoStopSeconds.collectAsStateWithLifecycle()
    val driverAlert by TrackingState.driverAlert.collectAsStateWithLifecycle()
    val travelBearing by TrackingState.latestBearingDegrees.collectAsStateWithLifecycle()
    val latestLat by TrackingState.latestLat.collectAsStateWithLifecycle()
    val latestLon by TrackingState.latestLon.collectAsStateWithLifecycle()
    suspend fun saveBoundaryMarker(marker: MarkerEntity) {
        if (marker.kind in setOf("speed","speed_advance","community_safety_zone_start","community_safety_zone_end","senior_safety_zone_start","senior_safety_zone_end","red_light_camera","camera")) {
            val nearby = dao.markersOfKindInBox(marker.kind, marker.latitude - 0.002, marker.latitude + 0.002, marker.longitude - 0.002, marker.longitude + 0.002)
            nearby.forEach { old ->
                val d = FloatArray(1)
                android.location.Location.distanceBetween(marker.latitude, marker.longitude, old.latitude, old.longitude, d)
                if (d[0] <= 120f) dao.deleteMarker(old.id)
            }
        }
        dao.insertMarker(marker)
    }

    fun pairId(note: String): String? = Regex("pair=([A-Za-z0-9-]+)").find(note)?.groupValues?.getOrNull(1)

    suspend fun deleteNearestZonePair(type: String): Boolean {
        val lat = TrackingState.latestLat.value ?: return false
        val lon = TrackingState.latestLon.value ?: return false
        val zoneMarkers = if (type == "community") dao.getCommunitySafetyZoneMarkers() else dao.getSeniorSafetyZoneMarkers()
        if (zoneMarkers.isEmpty()) return false
        fun distance(m: MarkerEntity): Float = FloatArray(1).also { android.location.Location.distanceBetween(lat, lon, m.latitude, m.longitude, it) }[0]
        val nearest = zoneMarkers.minByOrNull { distance(it) } ?: return false
        val pairedId = pairId(nearest.note)
        if (pairedId != null) {
            zoneMarkers.filter { pairId(it.note) == pairedId }.forEach { dao.deleteMarker(it.id) }
            return true
        }
        // Legacy markers without pair IDs retain the proximity fallback.
        val startKind = if (type == "community") "community_safety_zone_start" else "senior_safety_zone_start"
        val endKind = if (type == "community") "community_safety_zone_end" else "senior_safety_zone_end"
        val counterpartKind = if (nearest.kind == startKind) endKind else startKind
        val counterpart = zoneMarkers.filter { it.kind == counterpartKind }.minByOrNull { candidate ->
            FloatArray(1).also { android.location.Location.distanceBetween(nearest.latitude, nearest.longitude, candidate.latitude, candidate.longitude, it) }[0]
        }
        dao.deleteMarker(nearest.id)
        counterpart?.let { val d = FloatArray(1); android.location.Location.distanceBetween(nearest.latitude, nearest.longitude, it.latitude, it.longitude, d); if (d[0] <= 20_000f) dao.deleteMarker(it.id) }
        return true
    }

    fun speakPrompt(text: String) {
        context.startService(Intent(context, DriveTrackingService::class.java).apply {
            action = DriveTrackingService.ACTION_SPEAK
            putExtra(DriveTrackingService.EXTRA_SPEAK_TEXT, text)
        })
    }

    LaunchedEffect(activeDriveId, markers) {
        if (activeDriveId != null && markers.isNotEmpty()) {
            val community = markers.lastOrNull { it.kind == "community_safety_zone_start" || it.kind == "community_safety_zone_end" }
            val senior = markers.lastOrNull { it.kind == "senior_safety_zone_start" || it.kind == "senior_safety_zone_end" }
            var restored = emptySet<String>()
            if (community?.kind == "community_safety_zone_start") restored = restored + "community"
            if (senior?.kind == "senior_safety_zone_start") restored = restored + "senior"
            if (restored != TrackingState.activeZoneKinds.value) TrackingState.activeZoneKinds.value = restored

            // Keep only the newest marker when same-kind captures overlap in the same place.
            val dedupeKinds = setOf("red_light_camera","camera","deer_zone_enter","speed","speed_advance","community_safety_zone_start","community_safety_zone_end","senior_safety_zone_start","senior_safety_zone_end")
            val kept = mutableListOf<MarkerEntity>()
            markers.filter { it.kind in dedupeKinds }.sortedByDescending { it.timestamp }.forEach { candidate ->
                val overlapsNewer = kept.any { newer ->
                    if (newer.kind != candidate.kind) false else {
                        val d = FloatArray(1)
                        android.location.Location.distanceBetween(candidate.latitude, candidate.longitude, newer.latitude, newer.longitude, d)
                        d[0] <= 120f
                    }
                }
                if (overlapsNewer) dao.deleteMarker(candidate.id) else kept += candidate
            }
        }
    }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Recorded drives") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    drives.take(10).forEach { drive ->
                        TextButton(onClick = { selectedHistoryDriveId = drive.id; showHistory = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("Drive #${drive.id} • ${if (drive.endedAt == null) "recording" else "saved"}")
                        }
                    }
                    if (drives.isEmpty()) Text("No recorded drives yet")
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
        )
    }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 10.dp, vertical = 4.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // The collector card sizes itself to its active controls. The map below
            // automatically receives whatever screen space remains.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Collector", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            if (activeDriveId == null) scope.launch {
                                val id = dao.insertDrive(DriveEntity(startedAt = System.currentTimeMillis()))
                                dao.pruneOldDrives(10); selectedHistoryDriveId = null
                                ContextCompat.startForegroundService(context, Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_START; putExtra(DriveTrackingService.EXTRA_DRIVE_ID, id) })
                            } else {
                                context.startService(Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_STOP })
                                TrackingState.postDriverAlert("Drive saved", kind = "drive_saved")
                                context.stopService(Intent(context, CollectorOverlayService::class.java)); context.stopService(Intent(context, RouteMarkerMapOverlayService::class.java))
                            }
                        }, modifier = Modifier.height(34.dp), colors = ButtonDefaults.buttonColors(containerColor = if (activeDriveId == null) Color(0xFFDDEEDD) else Color(0xFFF4C7C3), contentColor = Color(0xFF263238)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (activeDriveId == null) "▶ Start" else "■ Stop", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { showHistory = true }, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("History", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { showSettings = !showSettings }, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("⚙") }
                    }
                    Text(
                        "${if (collectedSpeed) "Collected" else "Posted"} ${postedSpeed?.let { "$it km/h" } ?: "--"}   Actual ${actualSpeed?.let { "${it.toInt()} km/h" } ?: "--"}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    walkingCountdown?.let { Text("Walking detected • ending drive in ${it}s", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00)) }
                    cameraCaptureMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                dao.latestPoint(driveId)?.let { point ->
                                    val snap = IntersectionSnapper.findNearestIntersection(point.latitude, point.longitude)
                                    saveBoundaryMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = snap?.latitude ?: point.latitude, longitude = snap?.longitude ?: point.longitude, kind = "red_light_camera", note = snap?.let { "Red light camera — ${it.intersectionName}; observed ${point.latitude},${point.longitude}" } ?: "Red light camera — intersection not confirmed; observed ${point.latitude},${point.longitude}"))
                                    cameraCaptureMessage = if (snap != null) "✓ Camera marked • ${snap.intersectionName}" else "✓ Camera marked"
                                    scope.launch { kotlinx.coroutines.delay(4000); cameraCaptureMessage = null }
                                    speakPrompt(if (snap != null) "Marked camera at ${snap.intersectionName}" else "Red light camera marked")
                                }
                            }
                        }, enabled = activeDriveId != null, modifier = Modifier.wrapContentWidth().height(44.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = if ("camera" in activeRoadAlerts) Color(0xFFFFE0A3) else Color(0xFFF1F3F4), contentColor = Color(0xFF263238))) { Text("🚦📷", style = MaterialTheme.typography.titleLarge) }
                        Button(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                dao.latestPoint(driveId)?.let { point ->
                                    val bearing = travelBearing
                                    val existing = dao.getDeerZoneMarkers()
                                    var matchedDeer: MarkerEntity? = null
                                    val paired = bearing != null && existing.any { other ->
                                        val saved = Regex("bearing=(-?\\d+)").find(other.note)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.takeIf { it >= 0f } ?: return@any false
                                        val dist = FloatArray(1); android.location.Location.distanceBetween(point.latitude, point.longitude, other.latitude, other.longitude, dist)
                                        val raw = abs((((bearing % 360f) + 360f) % 360f) - (((saved % 360f) + 360f) % 360f)); val diff = if (raw > 180f) 360f - raw else raw
                                        (dist[0] <= 20_000f && diff >= 120f).also { if (it) matchedDeer = other }
                                    }
                                    val deerPairId = matchedDeer?.let { pairId(it.note) ?: UUID.randomUUID().toString() }
                                    if (matchedDeer != null && deerPairId != null && pairId(matchedDeer!!.note) == null) dao.updateMarker(matchedDeer!!.copy(note = matchedDeer!!.note + "; pair=$deerPairId"))
                                    dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude, kind = "deer_zone_enter", note = "Deer zone entering; bearing=${bearing?.roundToInt() ?: -1}" + (deerPairId?.let { "; pair=$it" } ?: "")))
                                    if (paired) ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply { startTone(ToneGenerator.TONE_PROP_BEEP2, 220); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release() }, 300) }
                                    speakPrompt(if (paired) "Deer zone captured" else "Deer zone marked")
                                }
                            }
                        }, enabled = activeDriveId != null, modifier = Modifier.wrapContentWidth().height(44.dp), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = if ("deer" in activeRoadAlerts) Color(0xFFFFE0A3) else Color(0xFFF1F3F4), contentColor = Color(0xFF263238))) { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Text("🦌", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF263238)); Box(Modifier.size(34.dp)) { Text("◆", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF111111)); Text("◆", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFFFD600), modifier = Modifier.padding(3.dp)) } } }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                dao.latestPoint(driveId)?.let { point ->
                                    val isActive = "community" in activeZones
                                    val pairKey = "active_pair_community"
                                    val zonePairId = if (isActive) prefs.getString(pairKey, null) ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
                                    saveBoundaryMarker(MarkerEntity(
                                        driveId = driveId, timestamp = System.currentTimeMillis(),
                                        latitude = point.latitude, longitude = point.longitude,
                                        kind = if (isActive) "community_safety_zone_end" else "community_safety_zone_start",
                                        note = (if (isActive) "Community safety zone end" else "Community safety zone start") + "; pair=$zonePairId"
                                    ))
                                    if (isActive) prefs.edit().remove(pairKey).apply() else prefs.edit().putString(pairKey, zonePairId).apply()
                                    TrackingState.activeZoneKinds.value =
                                        if (isActive) TrackingState.activeZoneKinds.value - "community"
                                        else TrackingState.activeZoneKinds.value + "community"
                                    speakPrompt(if (isActive) "Community safety end marked" else "Community safety start marked")
                                }
                            }
                        }, modifier = Modifier.weight(1.30f), colors = ButtonDefaults.buttonColors(containerColor = if ("community" in activeZones) Color(0xFFFFE0A3) else Color(0xFFDDEEDD), contentColor = Color(0xFF263238))) {
                            Text(if ("community" in activeZones) "Community ■" else "Community ▶")
                        }
                        Button(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                dao.latestPoint(driveId)?.let { point ->
                                    val isActive = "senior" in activeZones
                                    val pairKey = "active_pair_senior"
                                    val zonePairId = if (isActive) prefs.getString(pairKey, null) ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
                                    saveBoundaryMarker(MarkerEntity(
                                        driveId = driveId, timestamp = System.currentTimeMillis(),
                                        latitude = point.latitude, longitude = point.longitude,
                                        kind = if (isActive) "senior_safety_zone_end" else "senior_safety_zone_start",
                                        note = (if (isActive) "Senior safety zone end" else "Senior safety zone start") + "; pair=$zonePairId"
                                    ))
                                    if (isActive) prefs.edit().remove(pairKey).apply() else prefs.edit().putString(pairKey, zonePairId).apply()
                                    TrackingState.activeZoneKinds.value =
                                        if (isActive) TrackingState.activeZoneKinds.value - "senior"
                                        else TrackingState.activeZoneKinds.value + "senior"
                                    speakPrompt(if (isActive) "Senior safety end marked" else "Senior safety start marked")
                                }
                            }
                        }, modifier = Modifier.weight(1.0f), colors = ButtonDefaults.buttonColors(containerColor = if ("senior" in activeZones) Color(0xFFFFE0A3) else Color(0xFFDDEEDD), contentColor = Color(0xFF263238))) {
                            Text(if ("senior" in activeZones) "Senior ■" else "Senior ▶")
                        }
                        Button(onClick = {
                            if (!pendingSpeedChosen) markerSpeed = postedSpeed?.takeIf { it in listOf(30,40,50,60,70,80,90,100,110) } ?: 60
                            showSpeedMarker = !showSpeedMarker
                        }, modifier = Modifier.weight(0.90f), colors = ButtonDefaults.buttonColors(containerColor = if (overSpeedActive) Color(0xFFFFE0A3) else Color(0xFFDDEEDD), contentColor = Color(0xFF263238))) { Text("Speed", maxLines = 1) }
                    }
                    if (showSpeedMarker) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            var speedMenu by remember { mutableStateOf(false) }
                            var typeMenu by remember { mutableStateOf(false) }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { speedMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("$markerSpeed km/h") }
                                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                    listOf(30,40,50,60,70,80,90,100,110).forEach { speed ->
                                        DropdownMenuItem(
                                            text = { Text(if (speed == markerSpeed) "✓  $speed km/h" else "   $speed km/h") },
                                            onClick = { markerSpeed = speed; pendingSpeedChosen = true; speedMenu = false },
                                            colors = MenuDefaults.itemColors(textColor = if (speed == markerSpeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        )
                                    }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(markerType) }
                                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                    listOf("Zone begins","Advance sign").forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(if (type == markerType) "✓  $type" else "   $type") },
                                            onClick = { markerType = type; typeMenu = false },
                                            colors = MenuDefaults.itemColors(textColor = if (type == markerType) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        )
                                    }
                                }
                            }
                            Button(onClick = {
                                val driveId = activeDriveId
                                if (driveId != null) scope.launch {
                                    val point = dao.latestPoint(driveId)
                                    if (point != null) {
                                        val advance = markerType == "Advance sign"
                                        dao.insertMarker(MarkerEntity(
                                            driveId = driveId, timestamp = System.currentTimeMillis(),
                                            latitude = point.latitude, longitude = point.longitude,
                                            kind = if (advance) "speed_advance" else "speed",
                                            note = if (advance) "Speed limit $markerSpeed advance sign" else "Speed limit $markerSpeed"
                                        ))
                                        if (!advance) {
                                            TrackingState.currentSpeedIsCollected.value = true
                                            TrackingState.currentPostedSpeed.value = markerSpeed
                                        }
                                        speakPrompt(if (advance) "$markerSpeed kilometre advance sign marked" else "$markerSpeed kilometre zone start marked")
                                    }
                                }
                            }) { Text("Set") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                val deleted = dao.deleteLatestMarker(driveId)
                                speakPrompt(if (deleted > 0) "Last marker removed" else "No marker to remove")
                            }
                        }, enabled = activeDriveId != null) { Text("↶ Undo") }
                    }
                    if (showSettings) {
                        Text("Settings", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Visual alerts")
                            Switch(checked = visualAlerts, onCheckedChange = { visualAlerts = it; prefs.edit().putBoolean("visual_alerts_enabled", it).apply() })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Camera warning: $cameraWarning m")
                            Row {
                                TextButton(onClick = { cameraWarning = (cameraWarning - 50).coerceAtLeast(100); prefs.edit().putInt("red_light_camera_warning_metres", cameraWarning).apply() }) { Text("−50") }
                                TextButton(onClick = { cameraWarning = (cameraWarning + 50).coerceAtMost(500); prefs.edit().putInt("red_light_camera_warning_metres", cameraWarning).apply() }) { Text("+50") }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Speed tolerance: $toleranceSpeed +$toleranceValue")
                            Row {
                                TextButton(onClick = {
                                    toleranceSpeed = if (toleranceSpeed >= 110) 40 else toleranceSpeed + 10
                                    toleranceValue = prefs.getInt("speed_tolerance_$toleranceSpeed", if (toleranceSpeed >= 100) 9 else 8)
                                }) { Text("Speed") }
                                TextButton(onClick = { toleranceValue = (toleranceValue - 1).coerceAtLeast(0); prefs.edit().putInt("speed_tolerance_$toleranceSpeed", toleranceValue).apply() }) { Text("−") }
                                TextButton(onClick = { toleranceValue = (toleranceValue + 1).coerceAtMost(20); prefs.edit().putInt("speed_tolerance_$toleranceSpeed", toleranceValue).apply() }) { Text("+") }
                            }
                        }
                    }
                    if (driverAlert?.kind == "red_light_camera_remove_available" && driverAlert?.markerId != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Red-light camera here", modifier = Modifier.weight(1f))
                            TextButton(onClick = { TrackingState.driverAlert.value = null }) { Text("Keep") }
                            TextButton(onClick = {
                                val markerId = driverAlert?.markerId
                                if (markerId != null) scope.launch {
                                    dao.deleteMarker(markerId)
                                    TrackingState.driverAlert.value = null
                                    speakPrompt("Camera removed")
                                }
                            }) { Text("Remove") }
                        }
                    }
                    if (postedSpeed != null || activeZones.isNotEmpty() || activeRoadAlerts.isNotEmpty()) {
                        Text("Active zones", style = MaterialTheme.typography.labelMedium)
                        if (postedSpeed != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Speed: ${postedSpeed} km/h", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    context.startService(Intent(context, DriveTrackingService::class.java).apply {
                                        action = DriveTrackingService.ACTION_CLEAR_SPEED_ZONE
                                    })
                                }) { Text("Clear") }
                            }
                        }
                        if ("community" in activeZones) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Community safety", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    val driveId = activeDriveId
                                    if (driveId != null) scope.launch {
                                        dao.latestPoint(driveId)?.let { point ->
                                            dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude, kind = "community_safety_zone_end", note = "Community safety zone end"))
                                            TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "community"
                                            speakPrompt("Community safety zone cleared")
                                        }
                                    }
                                }) { Text("Clear") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val deleted = deleteNearestZonePair("community")
                                        TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "community"
                                        speakPrompt(if (deleted) "Community safety zone deleted" else "No community safety zone found")
                                    }
                                }) { Text("Delete") }
                            }
                        }
                        if ("senior" in activeZones) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Senior safety", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    val driveId = activeDriveId
                                    if (driveId != null) scope.launch {
                                        dao.latestPoint(driveId)?.let { point ->
                                            dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude, kind = "senior_safety_zone_end", note = "Senior safety zone end"))
                                            TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "senior"
                                            speakPrompt("Senior safety zone cleared")
                                        }
                                    }
                                }) { Text("Clear") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val deleted = deleteNearestZonePair("senior")
                                        TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "senior"
                                        speakPrompt(if (deleted) "Senior safety zone deleted" else "No senior safety zone found")
                                    }
                                }) { Text("Delete") }
                            }
                        }
                        if ("camera" in activeRoadAlerts) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Red-light camera", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    scope.launch {
                                        val lat = TrackingState.latestLat.value
                                        val lon = TrackingState.latestLon.value
                                        val nearest = if (lat != null && lon != null) dao.getSpokenRoadFacts()
                                            .filter { it.kind == "camera" || it.kind == "red_light_camera" }
                                            .minByOrNull { marker ->
                                                FloatArray(1).also { android.location.Location.distanceBetween(lat, lon, marker.latitude, marker.longitude, it) }[0]
                                            } else null
                                        if (nearest != null) dao.deleteMarker(nearest.id)
                                        TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "camera"
                                        speakPrompt(if (nearest != null) "Red light camera removed" else "Camera alert cleared")
                                    }
                                }) { Text("Remove") }
                            }
                        }
                        if ("deer" in activeRoadAlerts) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Deer crossing area", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "deer"
                                    speakPrompt("Deer zone cleared")
                                }) { Text("Clear") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val lat = TrackingState.latestLat.value
                                        val lon = TrackingState.latestLon.value
                                        val deer = dao.getDeerZoneMarkers()
                                        val nearest = if (lat != null && lon != null) deer.minByOrNull { marker ->
                                            FloatArray(1).also { android.location.Location.distanceBetween(lat, lon, marker.latitude, marker.longitude, it) }[0]
                                        } else null
                                        val id = nearest?.let { pairId(it.note) }
                                        if (id != null) deer.filter { pairId(it.note) == id }.forEach { dao.deleteMarker(it.id) }
                                        else if (nearest != null) dao.deleteMarker(nearest.id)
                                        TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "deer"
                                        speakPrompt(if (nearest != null) "Deer zone deleted" else "No deer zone found")
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }

                }
            }


                HorizontalDivider()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    RouteMap(
                        points = points,
                        markers = markers,
                        currentLat = latestLat,
                        currentLon = latestLon,
                        travelBearing = travelBearing,
                        activeZones = activeZones,
                        postedSpeed = postedSpeed,
                        collectedSpeedActive = collectedSpeed,
                        activeRoadAlerts = activeRoadAlerts,
                        actualSpeedKph = actualSpeed,
                        fitRoute = activeDriveId == null && selectedHistoryDriveId != null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
