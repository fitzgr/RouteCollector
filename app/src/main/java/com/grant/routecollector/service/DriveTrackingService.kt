package com.grant.routecollector.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.grant.routecollector.MainActivity
import com.grant.routecollector.R
import com.grant.routecollector.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DriveTrackingService : Service() {
    companion object {
        const val ACTION_START = "routecollector.START"
        const val ACTION_STOP = "routecollector.STOP"
        const val EXTRA_DRIVE_ID = "driveId"
        private const val CHANNEL_ID = "drive_tracking"
        private const val NOTIFICATION_ID = 101
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: FusedLocationProviderClient
    private val dao by lazy { AppDatabase.get(this).dao() }
    private var driveId: Long? = null

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
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                driveId = intent.getLongExtra(EXTRA_DRIVE_ID, -1L).takeIf { it > 0 }
                driveId?.let {
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
        .setContentText("GPS breadcrumb recording is active")
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
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
