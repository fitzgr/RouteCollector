package com.grant.routecollector.integration

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Shadow-only test transport. This class sends road-speed ADVISORIES to a test receiver.
 * It has no CAN, OBD, openpilot-control, or cruise-control functionality.
 * Call from a background/IO dispatcher, never the Android UI thread.
 */
class DriveBuddyShadowClient(private val endpointUrl: String, private val token: String) {
    init {
        require(endpointUrl.startsWith("http://") || endpointUrl.startsWith("https://"))
        require(token.isNotBlank())
    }

    fun send(
        targetSpeedKph: Int,
        collected: Boolean,
        driveId: Long,
        timestampMs: Long = System.currentTimeMillis(),
        manualTest: Boolean = false,
    ): Boolean {
        require(targetSpeedKph in 20..130)
        val body = JSONObject().apply {
            put("schema", "drivebuddy.speed_advisory.v1")
            put("mode", "shadow_only")
            put("targetSpeedKph", targetSpeedKph)
            put("source", if (manualTest) "manual_test" else if (collected) "collected" else "osm")
            put("driveId", driveId.toString())
            put("timestampMs", timestampMs)
        }.toString()

        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-DriveBuddy-Token", token)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            // A successful receiver explicitly says appliedToVehicle=false.
            if (connection.responseCode != 200) return false
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val acknowledgement = JSONObject(response)
            return acknowledgement.optBoolean("accepted", false) &&
                !acknowledgement.optBoolean("appliedToVehicle", true)
        } finally {
            connection.disconnect()
        }
    }
}
