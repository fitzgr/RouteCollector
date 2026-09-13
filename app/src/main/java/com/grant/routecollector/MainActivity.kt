package com.grant.routecollector

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.grant.routecollector.data.*
import com.grant.routecollector.service.DriveTrackingService
import com.grant.routecollector.ui.RouteMap
import com.grant.routecollector.voice.VoiceMarker
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date


class MainActivity : ComponentActivity() {
    private val dao by lazy { AppDatabase.get(this).dao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RouteCollectorScreen(this) } }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VoiceMarker.REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val words = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val note = words?.firstOrNull()?.trim().orEmpty()
            if (note.isNotBlank()) lifecycleScope.launch { saveMarkerFromLatest(note) }
        }
    }

    private suspend fun saveMarkerFromLatest(note: String) {
        val driveId = TrackingState.activeDriveId.value ?: return
        val point = dao.latestPoint(driveId) ?: return
        val lower = note.lowercase()
        val kind = when {
            "camera" in lower -> "camera"
            "speed" in lower -> "speed"
            "school" in lower -> "school_zone"
            else -> "note"
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
    }

    suspend fun saveQuickMarker(kind: String, note: String) {
        val driveId = TrackingState.activeDriveId.value ?: return
        val point = dao.latestPoint(driveId) ?: return
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteCollectorScreen(activity: MainActivity) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).dao() }
    val scope = rememberCoroutineScope()
    val activeDriveId by TrackingState.activeDriveId.collectAsStateWithLifecycle()
    val drives by dao.observeDrives().collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedDriveId by remember { mutableStateOf<Long?>(null) }

    val effectiveDriveId = activeDriveId ?: selectedDriveId
    val pointsFlow = remember(effectiveDriveId) {
        effectiveDriveId?.let { dao.observePoints(it) } ?: flowOf(emptyList())
    }
    val markersFlow = remember(effectiveDriveId) {
        effectiveDriveId?.let { dao.observeMarkers(it) } ?: flowOf(emptyList())
    }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val markers by markersFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Route Collector") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RouteMap(points = points, markers = markers, modifier = Modifier.fillMaxWidth().weight(1f))

            if (activeDriveId == null) {
                Button(
                    onClick = {
                        scope.launch {
                            val id = dao.insertDrive(DriveEntity(startedAt = System.currentTimeMillis()))
                            selectedDriveId = id
                            val intent = Intent(context, DriveTrackingService::class.java).apply {
                                action = DriveTrackingService.ACTION_START
                                putExtra(DriveTrackingService.EXTRA_DRIVE_ID, id)
                            }
                            ContextCompat.startForegroundService(context, intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start drive") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { activity.saveQuickMarker("camera", "Camera intersection") } },
                        modifier = Modifier.weight(1f)
                    ) { Text("Mark camera") }
                    Button(
                        onClick = { VoiceMarker.start(activity) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Voice marker") }
                }
                Button(
                    onClick = {
                        context.startService(Intent(context, DriveTrackingService::class.java).apply {
                            action = DriveTrackingService.ACTION_STOP
                        })
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Stop drive") }
            }

            Text("Recorded drives", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.heightIn(max = 180.dp)) {
                items(drives, key = { it.id }) { drive ->
                    TextButton(
                        onClick = { selectedDriveId = drive.id },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val started = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(drive.startedAt))
                        Text("$started  •  ${if (drive.endedAt == null) "recording" else "saved"}")
                    }
                }
            }
        }
    }
}
