package com.grant.routecollector.data

import kotlinx.coroutines.flow.MutableStateFlow

data class RecentZone(
    val markerId: Long,
    val kind: String,
    val label: String,
    val pairId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class DriverAlert(
    val text: String,
    val kind: String = "info",
    val markerId: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object TrackingState {
    val activeDriveId = MutableStateFlow<Long?>(null)
    val latestLat = MutableStateFlow<Double?>(null)
    val latestLon = MutableStateFlow<Double?>(null)
    val latestSpeedKph = MutableStateFlow<Float?>(null)
    val latestBearingDegrees = MutableStateFlow<Float?>(null)
    val currentPostedSpeed = MutableStateFlow<Int?>(null)
    val currentSpeedIsCollected = MutableStateFlow(false)
    val activeZoneKinds = MutableStateFlow<Set<String>>(emptySet())
    val activeRoadAlerts = MutableStateFlow<Set<String>>(emptySet())
    val overSpeedActive = MutableStateFlow(false)
    val walkingAutoStopSeconds = MutableStateFlow<Int?>(null)
    val driverAlert = MutableStateFlow<DriverAlert?>(null)
    val recentZones = MutableStateFlow<List<RecentZone>>(emptyList())

    fun rememberZone(markerId: Long, kind: String, label: String, note: String) {
        val pair = Regex("pair=([A-Za-z0-9-]+)").find(note)?.groupValues?.getOrNull(1)
        val item = RecentZone(markerId = markerId, kind = kind, label = label, pairId = pair)
        recentZones.value = (listOf(item) + recentZones.value.filterNot { it.markerId == markerId }).take(3)
    }

    fun dismissRecentZone(markerId: Long) {
        recentZones.value = recentZones.value.filterNot { it.markerId == markerId }
    }

    fun postDriverAlert(text: String, kind: String = "info", markerId: Long? = null) {
        driverAlert.value = DriverAlert(text = text, kind = kind, markerId = markerId)
    }
}
