package com.grant.routecollector.map

import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

object IntersectionSnapper {
    data class SnapResult(
        val latitude: Double,
        val longitude: Double,
        val intersectionName: String,
        val distanceMetres: Float,
        val source: String = "OpenStreetMap"
    )

    suspend fun findNearestIntersection(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double,
        radiusMetres: Int = 140
    ): SnapResult? = withContext(Dispatchers.IO) {
        // Prefer a true mapped intersection. A larger search radius handles the fact that
        // the driver often taps the camera button just before or after crossing the stop line.
        findOsmIntersection(latitude, longitude, radiusMetres)
            ?: findGeocodedRoadPair(geocoder, latitude, longitude)
    }

    private fun findOsmIntersection(latitude: Double, longitude: Double, radiusMetres: Int): SnapResult? {
        val query = """
            [out:json][timeout:8];
            way(around:$radiusMetres,$latitude,$longitude)[highway][name];
            out body;
            >;
            out skel qt;
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
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val elements = json.getJSONArray("elements")
            data class Way(val name: String, val nodeIds: List<Long>)
            val ways = mutableListOf<Way>()
            val nodes = mutableMapOf<Long, Pair<Double, Double>>()
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                when (element.optString("type")) {
                    "way" -> {
                        val name = element.optJSONObject("tags")?.optString("name").orEmpty()
                        val arr = element.optJSONArray("nodes") ?: continue
                        if (name.isBlank()) continue
                        val ids = buildList { for (j in 0 until arr.length()) add(arr.getLong(j)) }
                        ways += Way(name, ids)
                    }
                    "node" -> nodes[element.getLong("id")] = element.getDouble("lat") to element.getDouble("lon")
                }
            }
            val roadsByNode = mutableMapOf<Long, MutableSet<String>>()
            ways.forEach { way -> way.nodeIds.forEach { nodeId -> roadsByNode.getOrPut(nodeId) { linkedSetOf() }.add(way.name) } }
            roadsByNode.mapNotNull { (nodeId, roadNames) ->
                if (roadNames.size < 2) return@mapNotNull null
                val point = nodes[nodeId] ?: return@mapNotNull null
                val distance = FloatArray(1)
                Location.distanceBetween(latitude, longitude, point.first, point.second, distance)
                if (distance[0] > radiusMetres) return@mapNotNull null
                val names = roadNames.distinct().take(2).sorted()
                SnapResult(point.first, point.second, names.joinToString(" & "), distance[0], "OpenStreetMap")
            }.minByOrNull { it.distanceMetres }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun findGeocodedRoadPair(geocoder: Geocoder, latitude: Double, longitude: Double): SnapResult? {
        // Android's Geocoder is backed by the device geocoding service on Google-enabled
        // phones. Probe a small cross around the capture point and use two distinct road
        // names as a best-effort intersection label. The marker itself remains at the
        // original GPS point so a fallback can never move the camera to the wrong junction.
        val offsets = listOf(
            0.0 to 0.0, 0.00035 to 0.0, -0.00035 to 0.0,
            0.0 to 0.00045, 0.0 to -0.00045
        )
        val roads = linkedSetOf<String>()
        for ((dLat, dLon) in offsets) {
            val address = getAddress(geocoder, latitude + dLat, longitude + dLon) ?: continue
            address.thoroughfare?.trim()?.takeIf { it.isNotBlank() }?.let { roads += it }
            if (roads.size >= 2) break
        }
        if (roads.size < 2) return null
        return SnapResult(latitude, longitude, roads.take(2).sorted().joinToString(" & "), 0f, "device geocoder")
    }

    @Suppress("DEPRECATION")
    private fun getAddress(geocoder: Geocoder, latitude: Double, longitude: Double): Address? =
        runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
}
