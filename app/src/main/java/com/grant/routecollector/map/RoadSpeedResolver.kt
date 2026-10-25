package com.grant.routecollector.map

import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Best-effort posted-speed bootstrap from OpenStreetMap/Overpass.
 *
 * This is deliberately only a fallback source. Collected Route Collector speed-zone
 * markers remain authoritative once the vehicle crosses a matching directional marker.
 */
object RoadSpeedResolver {
    data class SpeedResult(
        val speedKph: Int,
        val roadName: String?,
        val distanceMetres: Float
    )

    fun findPostedSpeed(latitude: Double, longitude: Double, radiusMetres: Int = 45): SpeedResult? {
        val query = """
            [out:json][timeout:8];
            way(around:$radiusMetres,$latitude,$longitude)[highway][maxspeed];
            out tags geom;
        """.trimIndent()
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = (URL("https://overpass-api.de/api/interpreter?data=$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 7000
            setRequestProperty("User-Agent", "RouteCollector/0.1 Android")
        }

        return try {
            if (connection.responseCode !in 200..299) return null
            val elements = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("elements")
            var best: SpeedResult? = null

            for (i in 0 until elements.length()) {
                val way = elements.getJSONObject(i)
                if (way.optString("type") != "way") continue
                val tags = way.optJSONObject("tags") ?: continue
                val speed = parseMaxSpeed(tags.optString("maxspeed")) ?: continue
                // Route Collector currently collects Canadian road speeds in this range.
                if (speed !in 40..110) continue
                val geometry = way.optJSONArray("geometry") ?: continue
                var nearest = Float.MAX_VALUE
                for (j in 0 until geometry.length() - 1) {
                    val a = geometry.getJSONObject(j)
                    val b = geometry.getJSONObject(j + 1)
                    nearest = minOf(
                        nearest,
                        distanceToSegmentMetres(
                            latitude, longitude,
                            a.getDouble("lat"), a.getDouble("lon"),
                            b.getDouble("lat"), b.getDouble("lon")
                        )
                    )
                }
                if (nearest > radiusMetres) continue
                val candidate = SpeedResult(speed, tags.optString("name").takeIf { it.isNotBlank() }, nearest)
                if (best == null || candidate.distanceMetres < best!!.distanceMetres) best = candidate
            }
            best
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMaxSpeed(raw: String): Int? {
        val value = raw.trim().lowercase()
        if (value.isBlank() || value in setOf("none", "signals", "variable", "walk")) return null
        val number = Regex("\\d+").find(value)?.value?.toIntOrNull() ?: return null
        return if (value.contains("mph")) (number * 1.609344).toInt() else number
    }

    private fun distanceToSegmentMetres(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Float {
        // Local equirectangular projection is accurate enough for tens-of-metres road matching.
        val metresPerDegreeLat = 111_320.0
        val metresPerDegreeLon = 111_320.0 * cos(Math.toRadians(pLat))
        val ax = (aLon - pLon) * metresPerDegreeLon
        val ay = (aLat - pLat) * metresPerDegreeLat
        val bx = (bLon - pLon) * metresPerDegreeLon
        val by = (bLat - pLat) * metresPerDegreeLat
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val x = ax + t * dx
        val y = ay + t * dy
        return sqrt(x * x + y * y).toFloat()
    }
}
