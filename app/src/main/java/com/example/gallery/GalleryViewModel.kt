package com.example.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.CameraPreferences
import com.example.data.SpatialMediaRepository
import com.example.data.SpatialPhotoEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).spatialPhotoDao()
    private val repository = SpatialMediaRepository(application, dao)

    init {
        CameraPreferences.init(application)
    }

    val photos: StateFlow<List<SpatialPhotoEntity>> = repository.allPhotos
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val pixelDepthBoostEnabled: StateFlow<Boolean> = CameraPreferences.pixelDepthBoostEnabled
    val isArCoreSupported: StateFlow<Boolean?> = CameraPreferences.isArCoreSupported

    fun setPixelDepthBoost(enabled: Boolean) {
        CameraPreferences.setPixelDepthBoostEnabled(getApplication(), enabled)
    }

    fun deletePhoto(photo: SpatialPhotoEntity) {
        viewModelScope.launch {
            repository.deletePhoto(photo)
        }
    }
}
