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
    @Insert suspend fun insertMarker(marker: MarkerEntity)

    @Query("SELECT * FROM drives ORDER BY startedAt DESC")
    fun observeDrives(): Flow<List<DriveEntity>>

    @Query("SELECT * FROM drives WHERE id = :id LIMIT 1")
    suspend fun getDrive(id: Long): DriveEntity?

    @Query("SELECT * FROM track_points WHERE driveId = :driveId ORDER BY timestamp")
    fun observePoints(driveId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM markers WHERE driveId = :driveId ORDER BY timestamp")
    fun observeMarkers(driveId: Long): Flow<List<MarkerEntity>>

    @Query("SELECT * FROM track_points WHERE driveId = :driveId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestPoint(driveId: Long): TrackPointEntity?

    @Query("DELETE FROM markers WHERE id = (SELECT id FROM markers WHERE driveId = :driveId AND kind = 'camera' ORDER BY timestamp DESC LIMIT 1)")
    suspend fun deleteLatestCameraMarker(driveId: Long): Int
}
