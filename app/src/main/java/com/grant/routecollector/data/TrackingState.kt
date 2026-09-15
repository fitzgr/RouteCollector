package com.grant.routecollector.data

import kotlinx.coroutines.flow.MutableStateFlow

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
    val driverAlert = MutableStateFlow<DriverAlert?>(null)

    fun postDriverAlert(text: String, kind: String = "info", markerId: Long? = null) {
        driverAlert.value = DriverAlert(text = text, kind = kind, markerId = markerId)
    }
}
