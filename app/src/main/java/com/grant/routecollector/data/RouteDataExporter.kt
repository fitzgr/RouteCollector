package com.grant.routecollector.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
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
