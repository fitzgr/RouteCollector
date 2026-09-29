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
    @Update suspend fun updateMarker(marker: MarkerEntity)
    @Insert suspend fun insertPoint(point: TrackPointEntity)
    @Insert suspend fun insertMarker(marker: MarkerEntity): Long

    @Query("SELECT * FROM drives ORDER BY startedAt DESC")
    fun observeDrives(): Flow<List<DriveEntity>>

    @Query("SELECT * FROM drives ORDER BY startedAt")
    suspend fun getAllDrives(): List<DriveEntity>

    @Query("DELETE FROM drives WHERE id NOT IN (SELECT id FROM drives ORDER BY startedAt DESC LIMIT :keepCount)")
    suspend fun pruneOldDrives(keepCount: Int = 10): Int

    @Query("SELECT * FROM track_points ORDER BY timestamp")
    suspend fun getAllPoints(): List<TrackPointEntity>

    @Query("SELECT * FROM markers ORDER BY timestamp")
    suspend fun getAllMarkers(): List<MarkerEntity>

    @Query("SELECT * FROM markers ORDER BY timestamp")
    fun observeAllMarkers(): Flow<List<MarkerEntity>>

    @Query("SELECT * FROM drives WHERE id = :id LIMIT 1")
    suspend fun getDrive(id: Long): DriveEntity?

    @Query("SELECT * FROM track_points WHERE driveId = :driveId ORDER BY timestamp")
    fun observePoints(driveId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM markers WHERE driveId = :driveId ORDER BY timestamp")
    fun observeMarkers(driveId: Long): Flow<List<MarkerEntity>>

    @Query("SELECT * FROM track_points WHERE driveId = :driveId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestPoint(driveId: Long): TrackPointEntity?

    @Query("SELECT * FROM track_points WHERE driveId = :driveId AND timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp")
    suspend fun pointsInTimeRange(driveId: Long, startTime: Long, endTime: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE driveId = :driveId AND timestamp <= :timestamp ORDER BY timestamp DESC LIMIT 2")
    suspend fun pointsBeforeMarker(driveId: Long, timestamp: Long): List<TrackPointEntity>

    @Query("DELETE FROM markers WHERE id = :markerId")
    suspend fun deleteMarker(markerId: Long): Int

    @Query("DELETE FROM markers WHERE id = (SELECT id FROM markers WHERE driveId = :driveId ORDER BY timestamp DESC LIMIT 1)")
    suspend fun deleteLatestMarker(driveId: Long): Int
    @Query("SELECT * FROM markers WHERE driveId = :driveId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestMarker(driveId: Long): MarkerEntity?
    @Query("SELECT * FROM markers WHERE driveId = :driveId AND note LIKE '%' || :pairToken || '%' ORDER BY timestamp")
    suspend fun markersWithPairToken(driveId: Long, pairToken: String): List<MarkerEntity>

    @Query("SELECT * FROM markers WHERE kind = :kind AND latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLon AND :maxLon")
    suspend fun markersOfKindInBox(kind: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<MarkerEntity>

    @Query("SELECT * FROM markers WHERE kind = 'deer_zone_enter' ORDER BY timestamp")
    suspend fun getDeerZoneMarkers(): List<MarkerEntity>

    @Query("SELECT * FROM markers WHERE kind IN ('community_safety_zone_start', 'community_safety_zone_end') ORDER BY timestamp")
    suspend fun getCommunitySafetyZoneMarkers(): List<MarkerEntity>

    @Query("SELECT * FROM markers WHERE kind IN ('senior_safety_zone_start', 'senior_safety_zone_end') ORDER BY timestamp")
    suspend fun getSeniorSafetyZoneMarkers(): List<MarkerEntity>

    @Query("SELECT * FROM markers WHERE kind IN ('speed', 'speed_advance', 'school_zone', 'school_zone_start', 'school_zone_end', 'community_safety_zone_start', 'community_safety_zone_end', 'senior_safety_zone_start', 'senior_safety_zone_end', 'deer_zone_enter', 'camera', 'red_light_camera', 'pedestrian_crossing', 'passing_zone_start', 'passing_zone_end') ORDER BY timestamp DESC")
    suspend fun getSpokenRoadFacts(): List<MarkerEntity>
}
