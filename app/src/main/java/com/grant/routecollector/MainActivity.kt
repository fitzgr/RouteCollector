package com.grant.routecollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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

    val effectiveDriveId = activeDriveId ?: drives.firstOrNull()?.id
    val pointsFlow = remember(effectiveDriveId) { effectiveDriveId?.let { dao.observePoints(it) } ?: flowOf(emptyList()) }
    val markersFlow = remember(effectiveDriveId) { effectiveDriveId?.let { dao.observeMarkers(it) } ?: flowOf(emptyList()) }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val markers by markersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val postedSpeed by TrackingState.currentPostedSpeed.collectAsStateWithLifecycle()
    val actualSpeed by TrackingState.latestSpeedKph.collectAsStateWithLifecycle()
    val collectedSpeed by TrackingState.currentSpeedIsCollected.collectAsStateWithLifecycle()
    var showSpeedMarker by remember { mutableStateOf(false) }
    var markerSpeed by remember { mutableStateOf(60) }
    var markerType by remember { mutableStateOf("Zone begins") }

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
                        Text("Drive #${drive.id} • ${if (drive.endedAt == null) "recording" else "saved"}")
                    }
                    if (drives.isEmpty()) Text("No recorded drives yet")
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Route Collector") }) }) { padding ->
        Column(Modifier.padding(padding).padding(10.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Fixed collector controls: these are part of the app layout, not a floating overlay.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Collector", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showHistory = true }) { Text("History") }
                    }
                    Text("${if (collectedSpeed) "Collected" else "Posted"} ${postedSpeed?.let { "$it km/h" } ?: "--"}   Actual ${actualSpeed?.let { "${it.toInt()} km/h" } ?: "--"}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { /* camera capture remains in collector service until capture actions are shared */ }, enabled = false, modifier = Modifier.weight(1f)) { Text("🚦📷") }
                        Button(onClick = { /* deer capture remains in collector service until capture actions are shared */ }, enabled = false, modifier = Modifier.weight(1f)) { Text("◆ 🦌") }
                        Button(onClick = {
                            markerSpeed = postedSpeed?.takeIf { it in listOf(40,50,60,70,80,90,100,110) } ?: 60
                            markerType = "Zone begins"
                            showSpeedMarker = !showSpeedMarker
                        }, modifier = Modifier.weight(1f)) { Text("Speed") }
                    }
                    if (showSpeedMarker) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            var speedMenu by remember { mutableStateOf(false) }
                            var typeMenu by remember { mutableStateOf(false) }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { speedMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("$markerSpeed km/h") }
                                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                    listOf(40,50,60,70,80,90,100,110).forEach { speed ->
                                        DropdownMenuItem(text = { Text("$speed km/h") }, onClick = { markerSpeed = speed; speedMenu = false })
                                    }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(markerType) }
                                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                    listOf("Zone begins","Advance sign").forEach { type ->
                                        DropdownMenuItem(text = { Text(type) }, onClick = { markerType = type; typeMenu = false })
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
                                    }
                                }
                            }) { Text("Set") }
                        }
                    }
                }
            }

            Button(onClick = {
                if (activeDriveId == null) {
                    scope.launch {
                        val id = dao.insertDrive(DriveEntity(startedAt = System.currentTimeMillis()))
                        ContextCompat.startForegroundService(context, Intent(context, DriveTrackingService::class.java).apply {
                            action = DriveTrackingService.ACTION_START
                            putExtra(DriveTrackingService.EXTRA_DRIVE_ID, id)
                        })
                    }
                } else {
                    context.startService(Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_STOP })
                    context.stopService(Intent(context, CollectorOverlayService::class.java))
                    context.stopService(Intent(context, RouteMarkerMapOverlayService::class.java))
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (activeDriveId == null) "Start drive" else "Stop drive")
            }

            // Map owns only the remaining lower portion of the screen.
            RouteMap(points = points, markers = markers, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}
