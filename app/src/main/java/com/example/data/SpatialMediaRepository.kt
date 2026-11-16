package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.imaging.DecodedSpatialPhoto
import com.example.imaging.DynamicDepthCodec
import com.example.imaging.RefinedDepthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CapturedFrameData(
    val bitmap: Bitmap,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float
)

data class LoadedSpatialPhoto(
    val entity: SpatialPhotoEntity?,
    val decoded: DecodedSpatialPhoto,
    val sweepFrames: List<SweepFrameRecord>
)

class SpatialMediaRepository(
    private val context: Context,
    private val dao: SpatialPhotoDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    val allPhotos: Flow<List<SpatialPhotoEntity>> = dao.getAllPhotos()

    suspend fun saveCapturedSpatialPhoto(
        heroBitmap: Bitmap,
        depthResult: RefinedDepthResult,
        sweepFrames: List<CapturedFrameData>
    ): SpatialPhotoEntity = withContext(Dispatchers.Default) {
        val scanId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val stampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        val fileName = "3DCAM_$stampStr.jpg"

        // 1. Encode universal Dynamic Depth (GDepth XMP) JPEG
        val spatialJpegBytes = DynamicDepthCodec.encodeDynamicDepthJpeg(
            colorBitmap = heroBitmap,
            depthBitmap = depthResult.depthBitmap,
            nearMeters = depthResult.nearMeters,
            farMeters = depthResult.farMeters
        )

        // 2. Save primary photo to Android Photos library (MediaStore Pictures/3d Camera)
        val savedUri = MediaStoreHelper.saveSpatialJpegToPhotos(context, spatialJpegBytes, fileName)

        // 3. Save fast local preview & optional sweep frames in app-private storage
        val cacheDir = File(context.filesDir, "spatial_cache/$scanId").apply { mkdirs() }
        val previewFile = File(cacheDir, "spatial_master.jpg")
        withContext(Dispatchers.IO) {
            FileOutputStream(previewFile).use { it.write(spatialJpegBytes) }
        }

        val frameRecords = sweepFrames.mapIndexed { idx, frame ->
            val frameFile = File(cacheDir, "frame_$idx.jpg")
            withContext(Dispatchers.IO) {
                FileOutputStream(frameFile).use { out ->
                    frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
            }
            SweepFrameRecord(
                imagePath = frameFile.absolutePath,
                tx = frame.tx,
                ty = frame.ty,
                tz = frame.tz,
                qx = frame.qx,
                qy = frame.qy,
                qz = frame.qz,
                qw = frame.qw
            )
        }

        val entity = SpatialPhotoEntity(
            id = scanId,
            createdAt = timestamp,
            mediaStoreUri = savedUri?.toString() ?: Uri.fromFile(previewFile).toString(),
            previewImagePath = previewFile.absolutePath,
            nearDepthMeters = depthResult.nearMeters,
            farDepthMeters = depthResult.farMeters,
            hasMetricDepth = depthResult.hasMetricDepth,
            frameCount = sweepFrames.size.coerceAtLeast(1),
            sweepFramesJson = json.encodeToString(frameRecords)
        )

        dao.insertPhoto(entity)
        entity
    }

    suspend fun loadSpatialPhoto(
        photoId: String?,
        externalUriString: String?
    ): LoadedSpatialPhoto? = withContext(Dispatchers.IO) {
        if (!photoId.isNullOrBlank()) {
            val entity = dao.getPhotoById(photoId) ?: return@withContext null
            val bytes = readBytesForEntity(entity) ?: return@withContext null
            val decoded = DynamicDepthCodec.decodeDynamicDepthJpeg(bytes) ?: return@withContext null
            val frames = runCatching {
                json.decodeFromString<List<SweepFrameRecord>>(entity.sweepFramesJson)
            }.getOrDefault(emptyList())
            return@withContext LoadedSpatialPhoto(entity, decoded, frames)
        }

        if (!externalUriString.isNullOrBlank()) {
            val uri = Uri.parse(externalUriString)
            val bytes = MediaStoreHelper.readBytesFromUri(context, uri) ?: return@withContext null
            val decoded = DynamicDepthCodec.decodeDynamicDepthJpeg(bytes) ?: return@withContext null
            return@withContext LoadedSpatialPhoto(null, decoded, emptyList())
        }

        null
    }

    suspend fun exportQuestSbsStereo(loaded: LoadedSpatialPhoto): Uri? = withContext(Dispatchers.Default) {
        val depthBmp = loaded.decoded.depthBitmap ?: return@withContext null
        val sbsBytes = DynamicDepthCodec.synthesizeSideBySideStereoJpeg(
            colorBitmap = loaded.decoded.colorBitmap,
            depthBitmap = depthBmp
        )
        val stampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        MediaStoreHelper.saveVrSbsJpegToPhotos(
            context = context,
            jpegBytes = sbsBytes,
            displayName = "3DCAM_${stampStr}_SBS_3D.jpg"
        )
    }

    suspend fun deletePhoto(entity: SpatialPhotoEntity) = withContext(Dispatchers.IO) {
        MediaStoreHelper.deleteOwnedMediaStoreUri(context, entity.mediaStoreUri)
        val previewFile = File(entity.previewImagePath)
        previewFile.parentFile?.deleteRecursively()
        dao.deletePhotoById(entity.id)
    }

    private suspend fun readBytesForEntity(entity: SpatialPhotoEntity): ByteArray? {
        val localFile = File(entity.previewImagePath)
        if (localFile.exists()) {
            return runCatching { localFile.readBytes() }.getOrNull()
        }
        return runCatching {
            MediaStoreHelper.readBytesFromUri(context, Uri.parse(entity.mediaStoreUri))
        }.getOrNull()
    }
}
