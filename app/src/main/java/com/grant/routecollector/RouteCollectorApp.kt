package com.grant.routecollector

import android.app.Application
import org.osmdroid.config.Configuration

class RouteCollectorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
    }
}
