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
        Column(Modifier.padding(padding).padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        context.startService(Intent(context, CollectorOverlayService::class.java).apply { action = CollectorOverlayService.ACTION_SHOW })
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Collector") }
                OutlinedButton(onClick = { showHistory = true }) { Text("History") }
            }

            if (activeDriveId == null) {
                Button(onClick = {
                    scope.launch {
                        val id = dao.insertDrive(DriveEntity(startedAt = System.currentTimeMillis()))
                        val intent = Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_START; putExtra(DriveTrackingService.EXTRA_DRIVE_ID, id) }
                        ContextCompat.startForegroundService(context, intent)
                        context.startService(Intent(context, CollectorOverlayService::class.java).apply { action = CollectorOverlayService.ACTION_SHOW })
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Start drive") }
            } else {
                Button(onClick = {
                    context.startService(Intent(context, DriveTrackingService::class.java).apply { action = DriveTrackingService.ACTION_STOP })
                    context.stopService(Intent(context, CollectorOverlayService::class.java))
                    context.stopService(Intent(context, RouteMarkerMapOverlayService::class.java))
                }, modifier = Modifier.fillMaxWidth()) { Text("Stop drive") }
            }

            RouteMap(points = points, markers = markers, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}
