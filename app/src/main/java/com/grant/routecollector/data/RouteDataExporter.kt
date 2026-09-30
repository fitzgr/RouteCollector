package com.grant.routecollector.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RouteDataExporter {
    private const val AUTO_BACKUP_PREFIX = "routecollector-auto-"
    private const val MAX_AUTO_BACKUPS = 5

    suspend fun exportToDownloads(
        context: Context,
        dao: RouteDao,
        automatic: Boolean = false
    ): String {
        val root = JSONObject().put("format", "routecollector-export-v1")
            .put("exportedAt", System.currentTimeMillis())
            .put("automatic", automatic)

        val drives = JSONArray()
        dao.getAllDrives().forEach { d ->
            drives.put(
                JSONObject()
                    .put("id", d.id)
                    .put("startedAt", d.startedAt)
                    .put("endedAt", d.endedAt)
                    .put("title", d.title)
            )
        }

        val points = JSONArray()
        dao.getAllPoints().forEach { p ->
            points.put(
                JSONObject()
                    .put("id", p.id)
                    .put("driveId", p.driveId)
                    .put("timestamp", p.timestamp)
                    .put("latitude", p.latitude)
                    .put("longitude", p.longitude)
                    .put("accuracyMetres", p.accuracyMetres)
                    .put("speedMps", p.speedMps)
            )
        }

        val markers = JSONArray()
        dao.getAllMarkers().forEach { m ->
            markers.put(
                JSONObject()
                    .put("id", m.id)
                    .put("driveId", m.driveId)
                    .put("timestamp", m.timestamp)
                    .put("latitude", m.latitude)
                    .put("longitude", m.longitude)
                    .put("kind", m.kind)
                    .put("note", m.note)
            )
        }
        root.put("drives", drives).put("points", points).put("markers", markers)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CANADA).format(Date())
        val prefix = if (automatic) "routecollector-auto" else "routecollector"
        val fileName = "$prefix-$stamp.json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create export")
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString(2)) }
            ?: error("Could not open export")

        if (automatic) pruneOldAutomaticBackups(context)
        return fileName
    }

    suspend fun importFromJson(context: Context, dao: RouteDao, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("Could not open backup")
        val root = JSONObject(text)
        require(root.optString("format") == "routecollector-export-v1") { "Unsupported RouteCollector backup" }

        val drivesJson = root.optJSONArray("drives") ?: JSONArray()
        val pointsJson = root.optJSONArray("points") ?: JSONArray()
        val markersJson = root.optJSONArray("markers") ?: JSONArray()
        val idMap = mutableMapOf<Long, Long>()

        // Import as new rows rather than overwriting current data. This makes restore safe
        // to use on a fresh install and avoids destructive replacement on an existing one.
        for (i in 0 until drivesJson.length()) {
            val d = drivesJson.getJSONObject(i)
            val oldId = d.getLong("id")
            val newId = dao.insertDrive(
                DriveEntity(
                    startedAt = d.getLong("startedAt"),
                    endedAt = if (d.isNull("endedAt")) null else d.getLong("endedAt"),
                    title = d.optString("title", "Drive")
                )
            )
            idMap[oldId] = newId
        }

        var pointCount = 0
        for (i in 0 until pointsJson.length()) {
            val p = pointsJson.getJSONObject(i)
            val newDriveId = idMap[p.getLong("driveId")] ?: continue
            dao.insertPoint(
                TrackPointEntity(
                    driveId = newDriveId,
                    timestamp = p.getLong("timestamp"),
                    latitude = p.getDouble("latitude"),
                    longitude = p.getDouble("longitude"),
                    accuracyMetres = p.optDouble("accuracyMetres", 0.0).toFloat(),
                    speedMps = if (p.isNull("speedMps")) null else p.getDouble("speedMps").toFloat()
                )
            )
            pointCount++
        }

        var markerCount = 0
        for (i in 0 until markersJson.length()) {
            val m = markersJson.getJSONObject(i)
            val newDriveId = idMap[m.getLong("driveId")] ?: continue
            dao.insertMarker(
                MarkerEntity(
                    driveId = newDriveId,
                    timestamp = m.getLong("timestamp"),
                    latitude = m.getDouble("latitude"),
                    longitude = m.getDouble("longitude"),
                    kind = m.getString("kind"),
                    note = m.optString("note", "")
                )
            )
            markerCount++
        }
        return "${idMap.size} drives, $pointCount points, $markerCount markers restored"
    }

    private fun pruneOldAutomaticBackups(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("$AUTO_BACKUP_PREFIX%.json")
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC, ${MediaStore.Downloads._ID} DESC"

        val oldIds = mutableListOf<Long>()
        resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            var position = 0
            while (cursor.moveToNext()) {
                if (position >= MAX_AUTO_BACKUPS) oldIds += cursor.getLong(idColumn)
                position++
            }
        }
        oldIds.forEach { id ->
            resolver.delete(android.content.ContentUris.withAppendedId(collection, id), null, null)
        }
    }
}
