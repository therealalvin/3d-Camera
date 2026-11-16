package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaStoreHelper {

    private const val PHOTOS_RELATIVE_PATH = "Pictures/3d Camera"
    private const val VR_EXPORT_RELATIVE_PATH = "Pictures/3d Camera/VR Export"

    suspend fun saveSpatialJpegToPhotos(
        context: Context,
        jpegBytes: ByteArray,
        displayName: String
    ): Uri? = withContext(Dispatchers.IO) {
        insertJpeg(context, jpegBytes, displayName, PHOTOS_RELATIVE_PATH)
    }

    suspend fun saveVrSbsJpegToPhotos(
        context: Context,
        jpegBytes: ByteArray,
        displayName: String
    ): Uri? = withContext(Dispatchers.IO) {
        insertJpeg(context, jpegBytes, displayName, VR_EXPORT_RELATIVE_PATH)
    }

    suspend fun readBytesFromUri(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    suspend fun deleteOwnedMediaStoreUri(context: Context, uriString: String) = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext
        runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null)
            } else if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            }
        }
    }

    private fun insertJpeg(
        context: Context,
        jpegBytes: ByteArray,
        displayName: String,
        relativePath: String
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val insertedUri = runCatching { resolver.insert(collection, values) }.getOrNull()
        if (insertedUri != null) {
            val writeOk = runCatching {
                resolver.openOutputStream(insertedUri)?.use { out ->
                    out.write(jpegBytes)
                    out.flush()
                } ?: throw IllegalStateException("Null output stream")
            }.isSuccess

            if (writeOk) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    runCatching { resolver.update(insertedUri, doneValues, null, null) }
                }
                return insertedUri
            } else {
                runCatching { resolver.delete(insertedUri, null, null) }
            }
        }

        // Fallback to app-external Pictures folder if scoped MediaStore insertion fails (e.g., Robolectric/API<29)
        val fallbackDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
            "3dCamera"
        ).apply { mkdirs() }
        val fallbackFile = File(fallbackDir, displayName)
        fallbackFile.writeBytes(jpegBytes)
        return Uri.fromFile(fallbackFile)
    }
}
