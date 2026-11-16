package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpatialPhotoDao {
    @Query("SELECT * FROM spatial_photos ORDER BY createdAt DESC")
    fun getAllPhotos(): Flow<List<SpatialPhotoEntity>>

    @Query("SELECT * FROM spatial_photos WHERE id = :id LIMIT 1")
    suspend fun getPhotoById(id: String): SpatialPhotoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPhoto(photo: SpatialPhotoEntity)

    @Query("DELETE FROM spatial_photos WHERE id = :id")
    suspend fun deletePhotoById(id: String)
}
