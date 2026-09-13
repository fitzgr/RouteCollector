package com.grant.routecollector.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "drives")
data class DriveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val title: String = "Drive"
)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = DriveEntity::class,
        parentColumns = ["id"],
        childColumns = ["driveId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("driveId")]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driveId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val speedMps: Float?
)

@Entity(
    tableName = "markers",
    foreignKeys = [ForeignKey(
        entity = DriveEntity::class,
        parentColumns = ["id"],
        childColumns = ["driveId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("driveId")]
)
data class MarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driveId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val kind: String,
    val note: String
)
