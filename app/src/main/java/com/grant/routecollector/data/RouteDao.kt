package com.grant.routecollector.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Insert suspend fun insertDrive(drive: DriveEntity): Long
    @Update suspend fun updateDrive(drive: DriveEntity)
    @Insert suspend fun insertPoint(point: TrackPointEntity)
    @Insert suspend fun insertMarker(marker: MarkerEntity): Long

    @Query("SELECT * FROM drives ORDER BY startedAt DESC")
    fun observeDrives(): Flow<List<DriveEntity>>

    @Query("SELECT * FROM drives ORDER BY startedAt")
    suspend fun getAllDrives(): List<DriveEntity>

    @Query("SELECT * FROM track_points ORDER BY timestamp")
    suspend fun getAllPoints(): List<TrackPointEntity>

    @Query("SELECT * FROM markers ORDER BY timestamp")
    suspend fun getAllMarkers(): List<MarkerEntity>

    @Query("SELECT * FROM drives WHERE id = :id LIMIT 1")
    suspend fun getDrive(id: Long): DriveEntity?

    @Query("SELECT * FROM track_points WHERE driveId = :driveId ORDER BY timestamp")
    fun observePoints(driveId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM markers WHERE driveId = :driveId ORDER BY timestamp")
    fun observeMarkers(driveId: Long): Flow<List<MarkerEntity>>

    @Query("SELECT * FROM track_points WHERE driveId = :driveId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestPoint(driveId: Long): TrackPointEntity?

    @Query("SELECT * FROM track_points WHERE driveId = :driveId AND timestamp <= :timestamp ORDER BY timestamp DESC LIMIT 2")
    suspend fun pointsBeforeMarker(driveId: Long, timestamp: Long): List<TrackPointEntity>

    @Query("DELETE FROM markers WHERE id = :markerId")
    suspend fun deleteMarker(markerId: Long): Int

    @Query("DELETE FROM markers WHERE id = (SELECT id FROM markers WHERE driveId = :driveId ORDER BY timestamp DESC LIMIT 1)")
    suspend fun deleteLatestMarker(driveId: Long): Int

    @Query("SELECT * FROM markers WHERE kind IN ('speed', 'speed_advance', 'school_zone', 'school_zone_start', 'school_zone_end', 'community_safety_zone_start', 'community_safety_zone_end', 'senior_safety_zone_start', 'senior_safety_zone_end', 'camera', 'red_light_camera') ORDER BY timestamp DESC")
    suspend fun getSpokenRoadFacts(): List<MarkerEntity>
}
