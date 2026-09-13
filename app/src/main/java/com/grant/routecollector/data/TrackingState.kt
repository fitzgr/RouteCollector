package com.grant.routecollector.data

import kotlinx.coroutines.flow.MutableStateFlow

data class DriverAlert(
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

object TrackingState {
    val activeDriveId = MutableStateFlow<Long?>(null)
    val latestLat = MutableStateFlow<Double?>(null)
    val latestLon = MutableStateFlow<Double?>(null)
    val driverAlert = MutableStateFlow<DriverAlert?>(null)

    fun postDriverAlert(text: String) {
        driverAlert.value = DriverAlert(text)
    }
}
