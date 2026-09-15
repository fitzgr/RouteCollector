package com.grant.routecollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import java.text.DateFormat
import java.util.Date

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
    var selectedDriveId by remember { mutableStateOf<Long?>(null) }

    val effectiveDriveId = activeDriveId ?: selectedDriveId
    val pointsFlow = remember(effectiveDriveId) { effectiveDriveId?.let { dao.observePoints(it) } ?: flowOf(emptyList()) }
    val markersFlow = remember(effectiveDriveId) { effectiveDriveId?.let { dao.observeMarkers(it) } ?: flowOf(emptyList()) }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val markers by markersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    fun launchMapsWithOverlay() {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            return
        }
        context.startService(Intent(context, CollectorOverlayService::class.java).apply { action = CollectorOverlayService.ACTION_SHOW })
        context.startService(Intent(context, RouteMarkerMapOverlayService::class.java).apply { action = RouteMarkerMapOverlayService.ACTION_SHOW })
        val mapsIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
        if (mapsIntent != null) context.startActivity(mapsIntent) else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Route Collector") }) }) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RouteMap(points = points, markers = markers, modifier = Modifier.fillMaxWidth().weight(1f))
            if (activeDriveId == null) {
                Button(onClick = {
                    scope.launch {
                        val id = dao.insertDrive(DriveEntity(startedAt = System.currentTimeMillis())); selectedDriveId = id
                        val intent = Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_START; putExtra(DriveTrackingService.EXTRA_DRIVE_ID, id) }
                        ContextCompat.startForegroundService(context, intent)
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Start drive") }
            } else {
                Text("Hands-free collection uses the wake word ‘Route’. Example: Route speed 60.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { launchMapsWithOverlay() }, modifier = Modifier.fillMaxWidth()) { Text("Open Google Maps + overlay") }
                Button(onClick = {
                    context.startService(Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_STOP })
                    context.stopService(Intent(context, CollectorOverlayService::class.java)); context.stopService(Intent(context, RouteMarkerMapOverlayService::class.java))
                }, modifier = Modifier.fillMaxWidth()) { Text("Stop drive") }
            }
            Text("Recorded drives", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 180.dp)) {
                items(drives, key = { it.id }) { drive ->
                    TextButton(onClick = { selectedDriveId = drive.id }, modifier = Modifier.fillMaxWidth()) {
                        val started = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(drive.startedAt))
                        Text("$started  •  ${if (drive.endedAt == null) "recording" else "saved"}")
                    }
                }
            }
        }
    }
}
