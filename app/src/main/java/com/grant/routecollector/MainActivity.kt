package com.grant.routecollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import android.os.Looper
import android.location.Location
import android.location.Geocoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.net.Uri
import androidx.core.content.FileProvider
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.delay
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import com.grant.routecollector.map.IntersectionSnapper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class DriveSummary(
    val driveId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationMillis: Long,
    val distanceKm: Double,
    val averageSpeedKph: Double,
    val maxSpeedKph: Double,
    val hardBrakeCount: Int,
    val strongestBrakeG: Float,
    val thresholdG: Float,
    val startLabel: String,
    val endLabel: String
)

class MainActivity : ComponentActivity() {
    private var pipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { if (pipMode) RouteCollectorPipScreen() else RouteCollectorScreen() } }
    }

    private fun enterDrivingPictureInPicture() {
        if (TrackingState.activeDriveId.value == null || isInPictureInPictureMode) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Covers Home/Recents on devices that do not reliably auto-enter PiP.
        enterDrivingPictureInPicture()
    }

    override fun onStop() {
        // Back, Home and Recents can take slightly different lifecycle paths across
        // Android versions. If an active drive is recording, make one last attempt
        // to leave the activity visible as PiP before it disappears.
        if (!isChangingConfigurations && !isFinishing) {
            enterDrivingPictureInPicture()
        }
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
    }
}

@Composable
private fun RouteCollectorPipScreen() {
    val speed by TrackingState.latestSpeedKph.collectAsStateWithLifecycle()
    val postedSpeed by TrackingState.currentPostedSpeed.collectAsStateWithLifecycle()
    val bearing by TrackingState.latestBearingDegrees.collectAsStateWithLifecycle()
    var recordDotVisible by remember { mutableStateOf(true) }
    var lastHeading by remember { mutableStateOf<Float?>(null) }
    var previousPostedSpeed by remember { mutableStateOf(postedSpeed) }
    var signAlpha by remember { mutableStateOf(1f) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(650)
            recordDotVisible = !recordDotVisible
        }
    }
    // GPS course is unreliable while stationary. Keep the last moving heading.
    LaunchedEffect(bearing, speed) {
        val heading = bearing?.takeIf { it.isFinite() }
        if (heading != null && (lastHeading == null || (speed ?: 0f) > 2f)) {
            lastHeading = ((heading % 360f) + 360f) % 360f
        }
    }
    LaunchedEffect(postedSpeed) {
        signAlpha = 1f
        val changed = postedSpeed != previousPostedSpeed
        previousPostedSpeed = postedSpeed
        if (changed && postedSpeed != null) {
            // One brief pulse for a new limit; never flash continuously.
            signAlpha = 0.3f
            delay(220)
            signAlpha = 1f
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val scale = minOf(maxWidth.value / 270f, maxHeight.value / 96f, 1.5f)
            Row(
                modifier = Modifier.fillMaxSize().padding((8 * scale).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("●", color = Color.Red.copy(alpha = if (recordDotVisible) 1f else 0.18f),
                    fontSize = (14 * scale).sp,
                    modifier = Modifier.semantics { contentDescription = "Recording" })
                Column(
                    modifier = Modifier.size((58 * scale).dp, (72 * scale).dp)
                        .alpha(signAlpha)
                        .background(Color.White, RoundedCornerShape((7 * scale).dp))
                        .border((2 * scale).dp, Color.Black, RoundedCornerShape((7 * scale).dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("MAXIMUM", color = Color.Black, fontSize = (8 * scale).sp, maxLines = 1)
                    Text(postedSpeed?.toString() ?: "--", color = Color.Black,
                        fontSize = (29 * scale).sp, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(speed?.roundToInt()?.toString() ?: "--", fontSize = (32 * scale).sp, maxLines = 1)
                    Text("km/h", fontSize = (11 * scale).sp, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveTravelCompass(lastHeading, Modifier.size((52 * scale).dp))
                    Text(lastHeading?.let { compassDirection(it) } ?: "--", fontSize = (12 * scale).sp)
                }
            }
        }
    }
}

@Composable
private fun LiveTravelCompass(heading: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics {
        contentDescription = heading?.let { "Travel direction ${compassDirection(it)}" } ?: "Waiting for travel direction"
    }) {
        val radius = size.minDimension / 2f
        drawCircle(Color.White, radius)
        drawCircle(Color.Black, radius - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = radius * 0.32f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val baseline = -(paint.ascent() + paint.descent()) / 2f
        val inset = radius * 0.71f
        for ((label, x, y) in listOf(
            Triple("N", center.x, center.y - inset),
            Triple("E", center.x + inset, center.y),
            Triple("S", center.x, center.y + inset),
            Triple("W", center.x - inset, center.y)
        )) drawContext.canvas.nativeCanvas.drawText(label, x, y + baseline, paint)
        if (heading != null) {
            rotate(heading) {
                val needle = Path().apply {
                    moveTo(center.x, center.y - radius * 0.53f)
                    lineTo(center.x - radius * 0.16f, center.y + radius * 0.2f)
                    lineTo(center.x + radius * 0.16f, center.y + radius * 0.2f)
                    close()
                }
                drawPath(needle, Color(0xFFD32F2F))
                drawLine(Color.DarkGray, center, Offset(center.x, center.y + radius * 0.42f), 2.dp.toPx())
            }
            drawCircle(Color.Black, radius * 0.07f)
        } else {
            drawCircle(Color.Gray, radius * 0.08f)
        }
    }
}

private fun compassDirection(bearing: Float): String {
    val normalized = ((bearing % 360f) + 360f) % 360f
    val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((normalized + 22.5f) / 45f).toInt() % directions.size]
}

@Composable
private fun CollectorMarkerButton(symbol: String, symbolSize: Int, markerColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(symbol, fontSize = symbolSize.sp, color = Color.Black)
        Text("📍", fontSize = 18.sp, color = markerColor)
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
    var restoreStatus by remember { mutableStateOf<String?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                restoreStatus = "Restoring backup…"
                restoreStatus = try {
                    RouteDataExporter.importFromJson(context, dao, uri)
                } catch (e: Exception) {
                    "Restore failed: ${e.message ?: "invalid backup"}"
                }
            }
        }
    }
    var selectedHistoryDriveId by remember { mutableStateOf<Long?>(null) }
    var cameraCaptureMessage by remember { mutableStateOf<String?>(null) }
    var cameraCaptureBusy by remember { mutableStateOf(false) }
    var cameraSnapPreview by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var deerDeleteTarget by remember { mutableStateOf<MarkerEntity?>(null) }
    var historySegmentPoint by remember { mutableStateOf<TrackPointEntity?>(null) }
    var historyPointEdit by remember { mutableStateOf<TrackPointEntity?>(null) }
    var historyPointLat by remember { mutableStateOf("") }
    var historyPointLon by remember { mutableStateOf("") }
    var historySegmentEnd by remember { mutableStateOf<TrackPointEntity?>(null) }
    var historyMarkerEdit by remember { mutableStateOf<MarkerEntity?>(null) }
    var historySegmentSpeed by remember { mutableIntStateOf(60) }
    var historyEditMode by remember { mutableStateOf("point") }
    var showHistoryEditor by remember { mutableStateOf(false) }

    val effectiveDriveId = activeDriveId ?: selectedHistoryDriveId ?: drives.firstOrNull()?.id
    val pointsFlow = remember(effectiveDriveId) { effectiveDriveId?.let { dao.observePoints(it) } ?: flowOf(emptyList()) }
    val markersFlow = remember(effectiveDriveId) { effectiveDriveId?.let { dao.observeMarkers(it) } ?: flowOf(emptyList()) }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val markers by markersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val postedSpeed by TrackingState.currentPostedSpeed.collectAsStateWithLifecycle()
    val actualSpeed by TrackingState.latestSpeedKph.collectAsStateWithLifecycle()
    val collectedSpeed by TrackingState.currentSpeedIsCollected.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var driveSummary by remember { mutableStateOf<DriveSummary?>(null) }
    var autoStartArmed by remember { mutableStateOf(true) }
    var autoStartInProgress by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("Not checked") }
    var updateWebUrl by remember { mutableStateOf<String?>(null) }
    var latestBuildLabel by remember { mutableStateOf<String?>(null) }
    var latestApkUrl by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateReady by remember { mutableStateOf(false) }
    var updateCheckCount by remember { mutableIntStateOf(0) }
    var updateLastCheckedAt by remember { mutableStateOf<Long?>(null) }
    var latestBuildDuration by remember { mutableStateOf<String?>(null) }
    var latestChanges by remember { mutableStateOf<List<String>>(emptyList()) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val updatePrefs = remember { context.getSharedPreferences("routecollector_updates", android.content.Context.MODE_PRIVATE) }
    var dismissedUpdateBuild by remember { mutableIntStateOf(updatePrefs.getInt("dismissed_update_build", -1)) }
    var latestBuildCode by remember { mutableIntStateOf(-1) }
    val checkLatestUpdate: suspend () -> Unit = {
        updateBusy = true
        updateCheckCount += 1
        updateStatus = "Comparing installed version with GitHub…"
        try {
            var keepMonitoring = true
            while (keepMonitoring && (showSettings || updateCheckCount == 1)) {
                val manifestUrl = "https://github.com/fitzgr/RouteCollector/releases/download/latest-debug/update.json?check=" + System.currentTimeMillis()
                val manifestConnection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true; connectTimeout = 8000; readTimeout = 8000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                }
                val body = manifestConnection.inputStream.bufferedReader().use { it.readText() }
                manifestConnection.disconnect()
                val json = JSONObject(body)
                val code = json.getInt("versionCode")
                latestBuildCode = code
                val name = json.optString("versionName", code.toString())
                val built = json.optString("buildTime", "")
                latestBuildLabel = "$name • $built"
                latestBuildDuration = json.optString("buildDuration", "").takeIf { it.isNotBlank() }
                latestApkUrl = json.getString("apkUrl")
                val changes = json.optJSONArray("changes")
                latestChanges = if (changes != null) buildList {
                    for (i in 0 until changes.length()) add(changes.optString(i))
                }.filter { it.isNotBlank() } else emptyList()
                updateReady = code > BuildConfig.VERSION_CODE
                updateWebUrl = null

                if (updateReady) {
                    updateStatus = "New GitHub build available: $name (build $code)"
                    if (dismissedUpdateBuild != code) showUpdateDialog = true
                    keepMonitoring = false
                } else {
                    updateStatus = "Installed build ${BuildConfig.VERSION_CODE} is in sync with GitHub build $code. Checking Actions…"
                    val actionsUrl = "https://api.github.com/repos/fitzgr/RouteCollector/actions/runs?branch=feature/google-maps-overlay&per_page=10&check=" + System.currentTimeMillis()
                    val actionsConnection = (URL(actionsUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true; connectTimeout = 8000; readTimeout = 8000
                        useCaches = false
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("Cache-Control", "no-cache, no-store")
                    }
                    val actionsBody = actionsConnection.inputStream.bufferedReader().use { it.readText() }
                    actionsConnection.disconnect()
                    val runs = JSONObject(actionsBody).getJSONArray("workflow_runs")
                    var activeCount = 0
                    for (i in 0 until runs.length()) {
                        val run = runs.getJSONObject(i)
                        if (run.optString("event") == "push" && run.optString("status") != "completed") activeCount += 1
                    }
                    if (activeCount > 0) {
                        updateStatus = "GitHub Actions building ($activeCount active). Monitoring…"
                        delay(10_000)
                    } else {
                        updateStatus = "In sync — installed build ${BuildConfig.VERSION_CODE} matches latest GitHub build $code. No build running."
                        keepMonitoring = false
                    }
                }
            }
        } catch (_: Exception) {
            updateReady = false
            latestApkUrl = null
            updateStatus = "Could not complete GitHub update check"
            updateWebUrl = "https://github.com/fitzgr/RouteCollector/releases/tag/latest-debug"
        }
        updateLastCheckedAt = System.currentTimeMillis()
        updateBusy = false
    }
    LaunchedEffect(Unit) {
        delay(1200)
        checkLatestUpdate()
    }
    LaunchedEffect(showSettings) {
        if (showSettings && updateCheckCount > 0 && !updateBusy) checkLatestUpdate()
    }
    if (showUpdateDialog && updateReady) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Drive Buddy update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    latestBuildLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (latestChanges.isNotEmpty()) {
                        Text("What's new", style = MaterialTheme.typography.labelLarge)
                        latestChanges.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text("A newer test build is available.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Installed: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                    Text("Update is optional. Not now keeps this version running normally and won’t prompt again for this same build.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    val apkUrl = latestApkUrl
                    if (apkUrl != null && !updateBusy) scope.launch {
                        updateBusy = true
                        updateStatus = "Downloading..."
                        try {
                            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                            val apk = File(dir, "routecollector-update.apk")
                            val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                                instanceFollowRedirects = true; connectTimeout = 10000; readTimeout = 30000
                            }
                            connection.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                            connection.disconnect()
                            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            updateStatus = "Installer opened"
                        } catch (_: Exception) {
                            updateStatus = "Download failed - use the latest-debug release"
                            updateWebUrl = "https://github.com/fitzgr/RouteCollector/releases/tag/latest-debug"
                        }
                        updateBusy = false
                    }
                }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    if (latestBuildCode > 0) {
                        dismissedUpdateBuild = latestBuildCode
                        updatePrefs.edit().putInt("dismissed_update_build", latestBuildCode).apply()
                    }
                }) { Text("Not now") }
            }
        )
    }

    val prefs = remember { context.getSharedPreferences("routecollector_overlay", android.content.Context.MODE_PRIVATE) }
    var visualAlerts by remember { mutableStateOf(prefs.getBoolean("visual_alerts_enabled", true)) }
    var cameraWarning by remember { mutableIntStateOf(prefs.getInt("red_light_camera_warning_metres", 200)) }
    var toleranceSpeed by remember { mutableIntStateOf(60) }
    var toleranceValue by remember { mutableIntStateOf(prefs.getInt("speed_tolerance_60", 8)) }
    var hardBrakeThresholdG by remember { mutableFloatStateOf(prefs.getFloat("hard_brake_threshold_g", 0.30f)) }
    var markerSpeed by remember { mutableStateOf(60) }
    var pendingSpeedChosen by remember { mutableStateOf(false) }
    var markerType by remember { mutableStateOf("Zone begins") }
    val activeZones by TrackingState.activeZoneKinds.collectAsStateWithLifecycle()
    val activeRoadAlerts by TrackingState.activeRoadAlerts.collectAsStateWithLifecycle()
    val overSpeedActive by TrackingState.overSpeedActive.collectAsStateWithLifecycle()
    val walkingCountdown by TrackingState.walkingAutoStopSeconds.collectAsStateWithLifecycle()
    val driverAlert by TrackingState.driverAlert.collectAsStateWithLifecycle()
    val recentZones by TrackingState.recentZones.collectAsStateWithLifecycle()
    val hardBrakeActive by TrackingState.hardBrakeActive.collectAsStateWithLifecycle()
    val hardBrakeG by TrackingState.hardBrakeG.collectAsStateWithLifecycle()
    val hardBrakeCount by TrackingState.hardBrakeCount.collectAsStateWithLifecycle()
    val completedDriveId by TrackingState.completedDriveId.collectAsStateWithLifecycle()
    val travelBearing by TrackingState.latestBearingDegrees.collectAsStateWithLifecycle()
    val latestLat by TrackingState.latestLat.collectAsStateWithLifecycle()
    val latestLon by TrackingState.latestLon.collectAsStateWithLifecycle()
    val alertFlash = rememberInfiniteTransition(label = "road-alert-flash")
    val alertFlashPhase by alertFlash.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(550), repeatMode = RepeatMode.Reverse),
        label = "road-alert-phase"
    )
    fun flashingButtonColor(active: Boolean, normal: Color): Color =
        if (active && alertFlashPhase >= 0.5f) Color(0xFFFFB300) else normal


    LaunchedEffect(completedDriveId) {
        val id = completedDriveId ?: return@LaunchedEffect
        delay(500)
        val drive = dao.getDrive(id) ?: return@LaunchedEffect
        val tripPoints = dao.getPointsForDrive(id)
        val brakes = dao.getHardBrakesForDrive(id)
        if (tripPoints.isNotEmpty()) {
            var metres = 0.0
            tripPoints.zipWithNext().forEach { (a, b) ->
                val out = FloatArray(1)
                Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
                metres += out[0]
            }
            val speeds = tripPoints.mapNotNull { it.speedMps?.times(3.6f)?.toDouble() }
            fun brakeValue(note: String): Float = Regex("g=([0-9.]+)").find(note)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
            fun thresholdValue(note: String): Float = Regex("thresholdG=([0-9.]+)").find(note)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: hardBrakeThresholdG
            val first = tripPoints.first(); val last = tripPoints.last()
            val ended = drive.endedAt ?: last.timestamp
            driveSummary = DriveSummary(
                driveId = id,
                startedAt = drive.startedAt,
                endedAt = ended,
                durationMillis = (ended - drive.startedAt).coerceAtLeast(0L),
                distanceKm = metres / 1000.0,
                averageSpeedKph = speeds.average().takeIf { !it.isNaN() } ?: 0.0,
                maxSpeedKph = speeds.maxOrNull() ?: 0.0,
                hardBrakeCount = brakes.size,
                strongestBrakeG = brakes.maxOfOrNull { brakeValue(it.note) } ?: 0f,
                thresholdG = brakes.lastOrNull()?.let { thresholdValue(it.note) } ?: hardBrakeThresholdG,
                startLabel = "%.5f, %.5f".format(Locale.CANADA, first.latitude, first.longitude),
                endLabel = "%.5f, %.5f".format(Locale.CANADA, last.latitude, last.longitude)
            )
        }
        TrackingState.completedDriveId.value = null
    }

    DisposableEffect(activeDriveId, autoStartArmed) {
        if (activeDriveId != null || !autoStartArmed ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onDispose { }
        } else {
            val fused = LocationServices.getFusedLocationProviderClient(context)
            val sensors = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
            var lastMotionAt = 0L
            var speedSince = 0L
            val sensorListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
                    val magnitude = kotlin.math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                    if (magnitude >= 0.5f) lastMotionAt = SystemClock.elapsedRealtime()
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensors.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
            val autoCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (autoStartInProgress || TrackingState.activeDriveId.value != null) return
                    val loc = result.lastLocation ?: return
                    val speedKph = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
                    val now = SystemClock.elapsedRealtime()
                    val motionRecent = now - lastMotionAt <= 8_000L
                    if (speedKph >= 10f && motionRecent) {
                        if (speedSince == 0L) speedSince = now
                        if (now - speedSince >= 4_000L) {
                            autoStartInProgress = true
                            scope.launch {
                                val id = dao.insertDrive(DriveEntity(startedAt = System.currentTimeMillis(), title = "Auto-detected drive"))
                                dao.pruneOldDrives(10)
                                selectedHistoryDriveId = null
                                ContextCompat.startForegroundService(context, Intent(context, DriveTrackingService::class.java).apply {
                                    action = DriveTrackingService.ACTION_START
                                    putExtra(DriveTrackingService.EXTRA_DRIVE_ID, id)
                                })
                                TrackingState.postDriverAlert("Drive detected — recording started", kind = "auto_start")
                                autoStartInProgress = false
                            }
                        }
                    } else speedSince = 0L
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 3_000L).setMinUpdateIntervalMillis(2_000L).build()
            fused.requestLocationUpdates(request, autoCallback, Looper.getMainLooper())
            onDispose {
                fused.removeLocationUpdates(autoCallback)
                sensors.unregisterListener(sensorListener)
            }
        }
    }

    driveSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Drive summary") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val mins = summary.durationMillis / 60_000
                    Text("Duration: " + (mins / 60) + "h " + (mins % 60) + "m")
                    Text("Distance: %.1f km".format(Locale.CANADA, summary.distanceKm))
                    Text("Average speed: %.1f km/h".format(Locale.CANADA, summary.averageSpeedKph))
                    Text("Maximum speed: %.0f km/h".format(Locale.CANADA, summary.maxSpeedKph))
                    Text("Hard braking: " + summary.hardBrakeCount)
                    if (summary.hardBrakeCount > 0) Text("Strongest brake: %.2f g".format(Locale.CANADA, summary.strongestBrakeG))
                    Text("Braking threshold used: %.2f g".format(Locale.CANADA, summary.thresholdG))
                    Text("From: " + summary.startLabel, style = MaterialTheme.typography.bodySmall)
                    Text("To: " + summary.endLabel, style = MaterialTheme.typography.bodySmall)
                    Text("Hard-brake events are stored with GPS positions for comparison with Ajusto.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { driveSummary = null }) { Text("OK") } }
        )
    }

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
                        val markerCount = if (drive.id == effectiveDriveId) markers.size else null
                        val elapsed = ((drive.endedAt ?: System.currentTimeMillis()) - drive.startedAt).coerceAtLeast(0L)
                        val minutes = elapsed / 60_000L
                        TextButton(onClick = { selectedHistoryDriveId = drive.id; showHistory = false; showHistoryEditor = true; historySegmentPoint = null; historySegmentEnd = null }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                val dateText = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(drive.startedAt))
                                val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                                val startText = timeFormat.format(Date(drive.startedAt))
                                val endText = drive.endedAt?.let { timeFormat.format(Date(it)) } ?: "Recording"
                                Text("$dateText  $startText – $endText")
                                Text("${minutes} min" + (markerCount?.let { " • $it markers" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (drives.isEmpty()) Text("No recorded drives yet")
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
        )
    }

    historyPointEdit?.let { point ->
        AlertDialog(
            onDismissRequest = { historyPointEdit = null },
            title = { Text("Edit collected point") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Correct this GPS point or delete it from the recorded drive.")
                    OutlinedTextField(value = historyPointLat, onValueChange = { historyPointLat = it }, label = { Text("Latitude") }, singleLine = true)
                    OutlinedTextField(value = historyPointLon, onValueChange = { historyPointLon = it }, label = { Text("Longitude") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val lat = historyPointLat.toDoubleOrNull()
                    val lon = historyPointLon.toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) scope.launch {
                        dao.updatePoint(point.copy(latitude = lat, longitude = lon))
                        historyPointEdit = null
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { scope.launch { dao.deletePoint(point.id); historyPointEdit = null } }) { Text("Delete point") }
                    TextButton(onClick = { historyPointEdit = null }) { Text("Cancel") }
                }
            }
        )
    }

    if (historySegmentPoint != null && historySegmentEnd != null) {
        val startSelected = historySegmentPoint!!
        val endSelected = historySegmentEnd!!
        AlertDialog(
            onDismissRequest = { historySegmentPoint = null; historySegmentEnd = null },
            title = { Text("Edit road segment") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Set the posted speed between the two selected points.")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(40,50,60,70,80,90,100,110).forEach { speed -> TextButton(onClick = { historySegmentSpeed = speed }) { Text(if (speed == historySegmentSpeed) "[$speed]" else "$speed") } }
                }
            } },
            confirmButton = { TextButton(onClick = { scope.launch {
                val driveId = selectedHistoryDriveId ?: startSelected.driveId
                val ordered = listOf(startSelected, endSelected).sortedBy { it.timestamp }
                val pair = UUID.randomUUID().toString()
                dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = ordered[0].timestamp, latitude = ordered[0].latitude, longitude = ordered[0].longitude, kind = "speed", note = "Speed limit $historySegmentSpeed; edited segment start; pair=$pair"))
                dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = ordered[1].timestamp, latitude = ordered[1].latitude, longitude = ordered[1].longitude, kind = "speed", note = "Speed limit $historySegmentSpeed; edited segment end; pair=$pair"))
                historySegmentPoint = null; historySegmentEnd = null
            } }) { Text("Set segment") } },
            dismissButton = { TextButton(onClick = { historySegmentPoint = null; historySegmentEnd = null }) { Text("Cancel") } }
        )
    }
    historyMarkerEdit?.let { marker ->
        AlertDialog(
            onDismissRequest = { historyMarkerEdit = null },
            title = { Text("Collected marker") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(marker.kind.replace('_',' ')); Text(marker.note, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { scope.launch {
                val routePoint = points.minByOrNull { p -> FloatArray(1).also { android.location.Location.distanceBetween(marker.latitude, marker.longitude, p.latitude, p.longitude, it) }[0] }
                if (routePoint != null) dao.updateMarker(marker.copy(latitude = routePoint.latitude, longitude = routePoint.longitude, timestamp = routePoint.timestamp))
                historyMarkerEdit = null
            } }) { Text("Nudge to route") } },
            dismissButton = { Row {
                TextButton(onClick = { scope.launch {
                    val pair = pairId(marker.note)
                    val targets = if (pair != null) dao.markersWithPairToken(marker.driveId, "pair=$pair") else listOf(marker)
                    targets.forEach { dao.deleteMarker(it.id) }; historyMarkerEdit = null
                } }) { Text("Delete") }
                TextButton(onClick = { historyMarkerEdit = null }) { Text("Close") }
            } }
        )
    }

    deerDeleteTarget?.let { target ->
        val targetPairId = pairId(target.note)
        AlertDialog(
            onDismissRequest = { deerDeleteTarget = null },
            title = { Text(if (targetPairId != null) "Delete deer zone" else "Delete deer marker") },
            text = {
                Text(
                    if (targetPairId != null)
                        "Delete just this marker, or delete both markers that define this deer zone?"
                    else
                        "This is an unpaired deer marker. Delete it?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val deer = dao.getDeerZoneMarkers()
                        if (targetPairId != null) {
                            deer.filter { pairId(it.note) == targetPairId }.forEach { dao.deleteMarker(it.id) }
                            speakPrompt("Deer zone deleted")
                        } else {
                            dao.deleteMarker(target.id)
                            speakPrompt("Deer marker deleted")
                        }
                        TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "deer"
                        deerDeleteTarget = null
                    }
                }) { Text(if (targetPairId != null) "Delete zone" else "Delete marker") }
            },
            dismissButton = {
                Row {
                    if (targetPairId != null) {
                        TextButton(onClick = {
                            scope.launch {
                                val deer = dao.getDeerZoneMarkers()
                                dao.deleteMarker(target.id)
                                deer.filter { it.id != target.id && pairId(it.note) == targetPairId }.forEach { other ->
                                    val cleaned = other.note.replace(Regex(";?\\s*pair=[A-Za-z0-9-]+"), "").trim().trimEnd(';')
                                    dao.updateMarker(other.copy(note = cleaned))
                                }
                                TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "deer"
                                deerDeleteTarget = null
                                speakPrompt("Deer marker deleted")
                            }
                        }) { Text("Delete marker") }
                    }
                    TextButton(onClick = { deerDeleteTarget = null }) { Text("Cancel") }
                }
            }
        )
    }

    if (showHistoryEditor && activeDriveId == null && selectedHistoryDriveId != null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("History editor", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = { historyEditMode = "point"; historySegmentPoint = null; historySegmentEnd = null }) { Text(if (historyEditMode == "point") "✓ Points" else "Points") }
                    Button(onClick = { historyEditMode = "segment"; historySegmentPoint = null; historySegmentEnd = null }) { Text(if (historyEditMode == "segment") "✓ Speed" else "Speed") }
                    TextButton(onClick = {
                        showHistoryEditor = false
                        selectedHistoryDriveId = null
                        historySegmentPoint = null
                        historySegmentEnd = null
                        historyPointEdit = null
                        historyMarkerEdit = null
                    }) { Text("Done") }
                }
                Text(
                    if (historyEditMode == "point") "Pinch to zoom and pan. Tap a route point or marker to edit it."
                    else if (historySegmentPoint == null) "Pinch to zoom and tap the first point of the speed segment."
                    else "Tap the second point of the speed segment.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    RouteMap(
                        points = points,
                        markers = markers,
                        currentLat = null,
                        currentLon = null,
                        travelBearing = null,
                        activeZones = emptySet(),
                        postedSpeed = null,
                        collectedSpeedActive = false,
                        activeRoadAlerts = emptySet(),
                        actualSpeedKph = null,
                        fitRoute = true,
                        onHistoryPointSelected = { point ->
                            if (historyEditMode == "segment") {
                                if (historySegmentPoint == null) historySegmentPoint = point else historySegmentEnd = point
                            } else {
                                historyPointEdit = point
                                historyPointLat = point.latitude.toString()
                                historyPointLon = point.longitude.toString()
                            }
                        },
                        historySegmentStart = historySegmentPoint,
                        onHistoryMarkerSelected = { marker -> historyMarkerEdit = marker },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        return
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
                        Text("Drive Buddy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            if (activeDriveId == null) scope.launch {
                                autoStartArmed = true
                                val id = dao.insertDrive(DriveEntity(startedAt = System.currentTimeMillis()))
                                dao.pruneOldDrives(10); selectedHistoryDriveId = null
                                ContextCompat.startForegroundService(context, Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_START; putExtra(DriveTrackingService.EXTRA_DRIVE_ID, id) })
                            } else {
                                autoStartArmed = false
                                context.startService(Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_STOP })
                                TrackingState.postDriverAlert("Drive saved", kind = "drive_saved")
                                context.stopService(Intent(context, CollectorOverlayService::class.java)); context.stopService(Intent(context, RouteMarkerMapOverlayService::class.java))
                            }
                        }, modifier = Modifier.height(34.dp), colors = ButtonDefaults.buttonColors(containerColor = if (activeDriveId == null) Color(0xFFDDEEDD) else Color(0xFFF4C7C3), contentColor = Color(0xFF263238)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (activeDriveId == null) "▶ Start" else "■ Stop", style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = { showHistory = true }, modifier = Modifier.height(34.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0E0E0), contentColor = Color(0xFF37474F)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("History", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { showSettings = !showSettings }, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("⚙") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${if (collectedSpeed) "Collected" else "Posted"} ${postedSpeed?.let { "$it km/h" } ?: "Pending"}   Actual ${actualSpeed?.let { "${it.toInt()} km/h" } ?: "--"}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                val latest = dao.latestMarker(driveId)
                                if (latest == null) speakPrompt("No marker to remove") else {
                                    val pair = pairId(latest.note)
                                    val pairedKinds = setOf("community_safety_zone_start","community_safety_zone_end","senior_safety_zone_start","senior_safety_zone_end","passing_zone_start","passing_zone_end","deer_zone_enter")
                                    val targets = if (pair != null && latest.kind in pairedKinds) dao.markersWithPairToken(driveId, "pair=$pair") else listOf(latest)
                                    targets.forEach { dao.deleteMarker(it.id) }
                                    pair?.let { p -> TrackingState.recentZones.value = TrackingState.recentZones.value.filterNot { it.pairId == p } }
                                    speakPrompt(if (targets.size > 1) "Last zone removed" else "Last marker removed")
                                }
                            }
                        }, enabled = activeDriveId != null, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                            Text("↶ Undo", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    walkingCountdown?.let { Text("No GPS movement • auto-stop in ${it}s", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB26A00)) }
                    if (activeDriveId != null) {
                        Text(
                            if (hardBrakeActive) "⚠ HARD BRAKE  %.2f g".format(Locale.CANADA, hardBrakeG) else "Braking monitor • $hardBrakeCount event" + if (hardBrakeCount == 1) "" else "s",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hardBrakeActive) Color(0xFFC62828) else Color(0xFF455A64)
                        )
                    }
                    cameraCaptureMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32)) }
                    Row(
                        Modifier.fillMaxWidth()
                            .border(1.dp, Color(0xFFB7C9BD), RoundedCornerShape(12.dp))
                            .background(Color(0xFFF4F8F5), RoundedCornerShape(12.dp))
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("📍", fontSize = 22.sp)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null && !cameraCaptureBusy) {
                                cameraCaptureBusy = true
                                scope.launch {
                                dao.latestPoint(driveId)?.let { point ->
                                    val snap = IntersectionSnapper.findNearestIntersection(Geocoder(context, Locale.CANADA), point.latitude, point.longitude)
                                    saveBoundaryMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = snap?.latitude ?: point.latitude, longitude = snap?.longitude ?: point.longitude, kind = "red_light_camera", note = snap?.let { "Red light camera — ${it.intersectionName}; observed ${point.latitude},${point.longitude}" } ?: "Red light camera — intersection not confirmed; observed ${point.latitude},${point.longitude}"))
                                    cameraCaptureMessage = if (snap != null) "✓ ${snap.intersectionName}" else "✓ Camera saved • intersection name pending"
                                    cameraSnapPreview = snap?.let { it.latitude to it.longitude }
                                    scope.launch { kotlinx.coroutines.delay(6000); cameraCaptureMessage = null; cameraSnapPreview = null }
                                    speakPrompt(if (snap != null) "Camera marked at ${snap.intersectionName}" else "Camera marked. Intersection name pending")
                                }
                                kotlinx.coroutines.delay(750)
                                cameraCaptureBusy = false
                            }
                            }
                        }, enabled = activeDriveId != null && !cameraCaptureBusy, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = flashingButtonColor("camera" in activeRoadAlerts, Color(0xFFE3F2E6)), contentColor = Color(0xFF263238))) { Text("📷", fontSize = 29.sp) }
                        Button(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                dao.latestPoint(driveId)?.let { point ->
                                    val bearing = travelBearing
                                    val existing = dao.getDeerZoneMarkers()
                                    data class DeerCandidate(val marker: MarkerEntity, val distance: Float, val bearingDifference: Float)
                                    val candidates = if (bearing != null) existing.mapNotNull { other ->
                                        if (pairId(other.note) != null) return@mapNotNull null
                                        val saved = Regex("bearing=(-?\\d+)").find(other.note)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.takeIf { it >= 0f } ?: return@mapNotNull null
                                        val dist = FloatArray(1)
                                        android.location.Location.distanceBetween(point.latitude, point.longitude, other.latitude, other.longitude, dist)
                                        if (dist[0] > 2_000f) return@mapNotNull null
                                        val raw = abs((((bearing % 360f) + 360f) % 360f) - (((saved % 360f) + 360f) % 360f))
                                        DeerCandidate(other, dist[0], if (raw > 180f) 360f - raw else raw)
                                    } else emptyList()
                                    val matchedDeer = candidates.filter { it.bearingDifference >= 120f }.minByOrNull { it.distance }?.marker
                                    val duplicateStart = candidates.filter { it.bearingDifference <= 50f }.minByOrNull { it.distance }?.marker
                                    if (matchedDeer == null && duplicateStart != null) {
                                        speakPrompt("Deer zone already started")
                                        return@let
                                    }
                                    val deerPairId = matchedDeer?.let { UUID.randomUUID().toString() }
                                    if (matchedDeer != null && deerPairId != null) dao.updateMarker(matchedDeer.copy(note = matchedDeer.note + "; pair=$deerPairId"))
                                    dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude, kind = "deer_zone_enter", note = "Deer zone entering; bearing=${bearing?.roundToInt() ?: -1}" + (deerPairId?.let { "; pair=$it" } ?: "")))
                                    if (matchedDeer != null) ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply { startTone(ToneGenerator.TONE_PROP_BEEP2, 220); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release() }, 300) }
                                    speakPrompt(if (matchedDeer != null) "Deer zone captured" else "Deer zone marked")
                                }
                            }
                        }, enabled = activeDriveId != null, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = flashingButtonColor("deer" in activeRoadAlerts, Color(0xFFE3F2E6)), contentColor = Color(0xFF263238))) { Text("🦌", fontSize = 29.sp, color = Color(0xFF263238)) }
                        Button(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                dao.latestPoint(driveId)?.let { point ->
                                    dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude, kind = "pedestrian_crossing", note = "Pedestrian crossing"))
                                    speakPrompt("Pedestrian crossing marked")
                                }
                            }
                        }, enabled = activeDriveId != null, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = flashingButtonColor("pedestrian" in activeRoadAlerts, Color(0xFFE3F2E6)), contentColor = Color(0xFF263238))) { Text("🚸", fontSize = 29.sp, color = Color.Black) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .border(1.dp, Color(0xFFB7C9BD), RoundedCornerShape(12.dp))
                            .background(Color(0xFFF4F8F5), RoundedCornerShape(12.dp))
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("▶■", style = MaterialTheme.typography.labelLarge, color = Color(0xFF546E5A))
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = flashingButtonColor("community" in activeZones, Color(0xFFDDEEDD)), contentColor = Color(0xFF263238))) {
                            Text("Community", maxLines = 1)
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
                        }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = flashingButtonColor("senior" in activeZones, Color(0xFFDDEEDD)), contentColor = Color(0xFF263238))) {
                            Text("Senior", maxLines = 1)
                        }
                        Button(onClick = {
                            val driveId = activeDriveId
                            if (driveId != null) scope.launch {
                                dao.latestPoint(driveId)?.let { point ->
                                    val isActive = "passing" in activeZones
                                    val pairKey = "active_pair_passing"
                                    val pair = if (isActive) prefs.getString(pairKey, null) ?: UUID.randomUUID().toString() else UUID.randomUUID().toString()
                                    dao.insertMarker(MarkerEntity(driveId = driveId, timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude, kind = if (isActive) "passing_zone_end" else "passing_zone_start", note = (if (isActive) "Passing zone end" else "Passing zone start") + "; pair=$pair; bearing=${travelBearing?.roundToInt() ?: -1}"))
                                    if (isActive) prefs.edit().remove(pairKey).apply() else prefs.edit().putString(pairKey, pair).apply()
                                    TrackingState.activeZoneKinds.value = if (isActive) TrackingState.activeZoneKinds.value - "passing" else TrackingState.activeZoneKinds.value + "passing"
                                    speakPrompt(if (isActive) "Passing zone end marked" else "Passing zone start marked")
                                }
                            }
                        }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp), colors = ButtonDefaults.buttonColors(containerColor = flashingButtonColor("passing" in activeRoadAlerts, Color(0xFFDDEEDD)), contentColor = Color(0xFF263238))) { Text("Passing", maxLines = 1) }
                        }
                    }
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
                                            note = (if (advance) "Speed limit $markerSpeed advance sign" else "Speed limit $markerSpeed") + "; bearing=${travelBearing?.roundToInt() ?: -1}"
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
                    if (showSettings) {
                        Text("Settings", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    restoreStatus = try {
                                        "Saved " + RouteDataExporter.exportToDownloads(context, dao)
                                    } catch (e: Exception) {
                                        "Backup failed: ${e.message ?: "unknown error"}"
                                    }
                                }
                            }) { Text("Backup JSON") }
                            OutlinedButton(onClick = {
                                restoreLauncher.launch(arrayOf("application/json", "text/plain"))
                            }) { Text("Restore JSON") }
                        }
                        restoreStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text("Installed: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                        latestBuildLabel?.let { Text("Latest: $it", style = MaterialTheme.typography.bodySmall) }
                        latestBuildDuration?.let { Text("Last build duration: $it", style = MaterialTheme.typography.bodySmall) }
                        if (latestChanges.isNotEmpty()) {
                            Text("What’s new", style = MaterialTheme.typography.labelLarge)
                            latestChanges.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        Text("Update: $updateStatus", style = MaterialTheme.typography.bodySmall)
                        updateLastCheckedAt?.let { checked ->
                            Text("Last checked: " + java.text.SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(checked)) + " • check #$updateCheckCount", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (!updateBusy) scope.launch { checkLatestUpdate() }
                            }, enabled = !updateBusy) { Text(if (updateBusy) "Checking..." else "Check now") }
                            Button(onClick = {
                                val apkUrl = latestApkUrl
                                if (apkUrl != null && !updateBusy) scope.launch {
                                    updateBusy = true; updateStatus = "Downloading..."
                                    try {
                                        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                                        val apk = File(dir, "routecollector-update.apk")
                                        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout = 10000; readTimeout = 30000 }
                                        connection.inputStream.use { input -> apk.outputStream().use { output -> input.copyTo(output) } }; connection.disconnect()
                                        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
                                        context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) })
                                        updateStatus = "Installer opened"
                                    } catch (_: Exception) { updateStatus = "Download failed - GitHub sign-in may be required" }
                                    updateBusy = false
                                }
                            }, enabled = updateReady && latestApkUrl != null && !updateBusy) { Text("Update") }
                            updateWebUrl?.let { url ->
                                Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Open latest build") }
                            }
                        }
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
                            Text("Hard braking: %.2f g".format(Locale.CANADA, hardBrakeThresholdG))
                            Row {
                                TextButton(onClick = {
                                    hardBrakeThresholdG = (hardBrakeThresholdG - 0.05f).coerceAtLeast(0.15f)
                                    prefs.edit().putFloat("hard_brake_threshold_g", hardBrakeThresholdG).apply()
                                }) { Text("−") }
                                TextButton(onClick = {
                                    hardBrakeThresholdG = (hardBrakeThresholdG + 0.05f).coerceAtMost(0.60f)
                                    prefs.edit().putFloat("hard_brake_threshold_g", hardBrakeThresholdG).apply()
                                }) { Text("+") }
                            }
                        }
                        Text("0.30 g is the starting calibration. Compare Drive Buddy events with Ajusto and tune this over time.", style = MaterialTheme.typography.bodySmall)
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
                    if (recentZones.isNotEmpty()) {
                        Text("Recent zones", style = MaterialTheme.typography.labelMedium)
                        recentZones.forEachIndexed { index, zone ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                val recentLabel = if (zone.kind == "passing") "Oncoming traffic" else zone.label
                                Text(recentLabel, modifier = Modifier.weight(1f), maxLines = 1)
                                if (index == 0 && recentZones.size > 1) {
                                    TextButton(onClick = {
                                        recentZones.toList().forEach { recent -> TrackingState.dismissRecentZone(recent.markerId) }
                                    }) { Text("Keep all") }
                                }
                                TextButton(onClick = { TrackingState.dismissRecentZone(zone.markerId) }) { Text("Keep") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val facts = dao.getSpokenRoadFacts()
                                        val targets = if (zone.pairId != null) facts.filter { pairId(it.note) == zone.pairId } else facts.filter { it.id == zone.markerId }
                                        targets.forEach { dao.deleteMarker(it.id) }
                                        TrackingState.dismissRecentZone(zone.markerId)
                                        when (zone.kind) {
                                            "community", "senior", "passing" -> TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - zone.kind
                                            "deer" -> TrackingState.activeRoadAlerts.value = TrackingState.activeRoadAlerts.value - "deer"
                                            "speed" -> context.startService(Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_CLEAR_SPEED_ZONE })
                                        }
                                        speakPrompt(if (targets.isNotEmpty()) "$recentLabel deleted" else "Zone already removed")
                                    }
                                }) { Text("Delete") }
                            }
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
                                }) { Text("Keep") }
                            }
                        }
                        if ("community" in activeZones) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Community safety", modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "community"
                                    speakPrompt("Community safety zone kept")
                                }) { Text("Keep") }
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
                                    TrackingState.activeZoneKinds.value = TrackingState.activeZoneKinds.value - "senior"
                                    speakPrompt("Senior safety zone kept")
                                }) { Text("Keep") }
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
                                }) { Text("Keep") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val lat = TrackingState.latestLat.value
                                        val lon = TrackingState.latestLon.value
                                        val deer = dao.getDeerZoneMarkers()
                                        deerDeleteTarget = if (lat != null && lon != null) deer.minByOrNull { marker ->
                                            FloatArray(1).also { android.location.Location.distanceBetween(lat, lon, marker.latitude, marker.longitude, it) }[0]
                                        } else null
                                        if (deerDeleteTarget == null) speakPrompt("No deer zone found")
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }

                }
            }


                if (activeDriveId == null && selectedHistoryDriveId != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { historyEditMode = "point"; historySegmentPoint = null; historySegmentEnd = null }, modifier = Modifier.weight(1f)) { Text(if (historyEditMode == "point") "✓ Point edit" else "Point edit") }
                        Button(onClick = { historyEditMode = "segment"; historySegmentPoint = null; historySegmentEnd = null }, modifier = Modifier.weight(1f)) { Text(if (historyEditMode == "segment") "✓ Speed segment" else "Speed segment") }
                    }
                    Text(if (historyEditMode == "point") "Tap a collected GPS point to correct or delete it." else if (historySegmentPoint == null) "Tap the first point of the speed segment." else "Tap the second point of the speed segment.", style = MaterialTheme.typography.bodySmall)
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
                        onHistoryPointSelected = if (activeDriveId == null && selectedHistoryDriveId != null) { point ->
                            if (historyEditMode == "segment") {
                                if (historySegmentPoint == null) historySegmentPoint = point else historySegmentEnd = point
                            } else {
                                historyPointEdit = point
                                historyPointLat = point.latitude.toString()
                                historyPointLon = point.longitude.toString()
                            }
                        } else null,
                        historySegmentStart = historySegmentPoint,
                        onHistoryMarkerSelected = if (activeDriveId == null && selectedHistoryDriveId != null) { marker -> historyMarkerEdit = marker } else null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
