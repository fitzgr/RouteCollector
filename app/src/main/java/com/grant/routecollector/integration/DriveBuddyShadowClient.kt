package com.grant.routecollector.integration

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Advisory-only Drive Buddy speed suggestion. NEVER commands Toyota, CAN or
 * openpilot cruise/longitudinal control.
 */
class DriveBuddyShadowClient(private val endpointUrl: String, private val token: String) {
    companion object {
        // User-requested *display-only* preference. This may exceed posted limits.
        const val SPEED_OFFSET_KPH = 8
    }

    init {
        require(endpointUrl.startsWith("http://") || endpointUrl.startsWith("https://"))
        require(token.length >= 16)
    }

    fun send(
        postedSpeedKph: Int,
        collected: Boolean,
        driveId: Long,
        timestampMs: Long = System.currentTimeMillis(),
        manualTest: Boolean = false,
    ): Boolean {
        require(postedSpeedKph in 20..130)
        val suggestedCruiseKph = postedSpeedKph + SPEED_OFFSET_KPH
        val body = JSONObject().apply {
            put("schema", "drivebuddy.speed_advisory.v2")
            put("mode", "shadow_only")
            put("postedSpeedKph", postedSpeedKph)
            put("offsetKph", SPEED_OFFSET_KPH)
            put("targetSpeedKph", suggestedCruiseKph)
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
            if (connection.responseCode != 200) return false
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val ack = JSONObject(response)
            return ack.optBoolean("accepted", false) &&
                !ack.optBoolean("appliedToVehicle", true) &&
                ack.optInt("postedSpeedKph", -1) == postedSpeedKph &&
                ack.optInt("targetSpeedKph", -1) == suggestedCruiseKph
        } finally {
            connection.disconnect()
        }
    }
}
