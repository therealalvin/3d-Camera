package com.example.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.LoadedSpatialPhoto
import com.example.data.SpatialMediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ViewerUiState {
    data object Loading : ViewerUiState
    data class Ready(
        val photo: LoadedSpatialPhoto,
        val isExportingVr: Boolean = false,
        val vrSavedSuccess: Boolean = false,
        val errorMessage: String? = null
    ) : ViewerUiState
    data class Error(val message: String) : ViewerUiState
}

class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).spatialPhotoDao()
    private val repository = SpatialMediaRepository(application, dao)

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    fun load(photoId: String?, externalUri: String?) {
        _uiState.value = ViewerUiState.Loading
        viewModelScope.launch {
            val loaded = repository.loadSpatialPhoto(photoId, externalUri)
            if (loaded != null) {
                _uiState.value = ViewerUiState.Ready(photo = loaded)
            } else {
                _uiState.value = ViewerUiState.Error("Could not open photo")
            }
        }
    }

    fun exportQuestVrSbs() {
        val current = _uiState.value as? ViewerUiState.Ready ?: return
        if (current.isExportingVr || !current.photo.decoded.hasDepthMap) return

        _uiState.value = current.copy(isExportingVr = true, vrSavedSuccess = false, errorMessage = null)
        viewModelScope.launch {
            val uri = repository.exportQuestSbsStereo(current.photo)
            val latest = _uiState.value as? ViewerUiState.Ready ?: return@launch
            _uiState.value = if (uri != null) {
                latest.copy(isExportingVr = false, vrSavedSuccess = true)
            } else {
                latest.copy(isExportingVr = false, errorMessage = "Could not export 3D VR photo")
            }
        }
    }

    fun clearVrStatus() {
        val current = _uiState.value as? ViewerUiState.Ready ?: return
        _uiState.value = current.copy(vrSavedSuccess = false, errorMessage = null)
    }

    fun deleteCurrentPhoto(onDeleted: () -> Unit) {
        val current = _uiState.value as? ViewerUiState.Ready ?: return
        val entity = current.photo.entity ?: return
        viewModelScope.launch {
            repository.deletePhoto(entity)
            onDeleted()
        }
    }
}
