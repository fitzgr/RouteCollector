package com.grant.routecollector.data

import kotlinx.coroutines.flow.MutableStateFlow

object TrackingState {
    val activeDriveId = MutableStateFlow<Long?>(null)
    val latestLat = MutableStateFlow<Double?>(null)
    val latestLon = MutableStateFlow<Double?>(null)
}
