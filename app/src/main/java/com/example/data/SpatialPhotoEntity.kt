package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class SweepFrameRecord(
    val imagePath: String,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float
)

@Entity(tableName = "spatial_photos")
data class SpatialPhotoEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val mediaStoreUri: String,
    val previewImagePath: String,
    val nearDepthMeters: Float,
    val farDepthMeters: Float,
    val hasMetricDepth: Boolean,
    val frameCount: Int,
    val sweepFramesJson: String
)
