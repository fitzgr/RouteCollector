package com.grant.routecollector.map

import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object IntersectionSnapper {
    data class SnapResult(
        val latitude: Double,
        val longitude: Double,
        val intersectionName: String,
        val distanceMetres: Float
    )

    fun findNearestIntersection(latitude: Double, longitude: Double, radiusMetres: Int = 120): SnapResult? {
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
                        val ids = buildList {
                            for (j in 0 until arr.length()) add(arr.getLong(j))
                        }
                        ways += Way(name, ids)
                    }
                    "node" -> {
                        nodes[element.getLong("id")] = element.getDouble("lat") to element.getDouble("lon")
                    }
                }
            }

            val roadsByNode = mutableMapOf<Long, MutableSet<String>>()
            for (way in ways) {
                for (nodeId in way.nodeIds) roadsByNode.getOrPut(nodeId) { linkedSetOf() }.add(way.name)
            }

            var best: SnapResult? = null
            for ((nodeId, roadNames) in roadsByNode) {
                if (roadNames.size < 2) continue
                val point = nodes[nodeId] ?: continue
                val distance = FloatArray(1)
                Location.distanceBetween(latitude, longitude, point.first, point.second, distance)
                if (distance[0] > radiusMetres) continue
                val names = roadNames.take(2).sorted()
                val candidate = SnapResult(
                    latitude = point.first,
                    longitude = point.second,
                    intersectionName = names.joinToString(" & "),
                    distanceMetres = distance[0]
                )
                if (best == null || candidate.distanceMetres < best!!.distanceMetres) best = candidate
            }
            best
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
