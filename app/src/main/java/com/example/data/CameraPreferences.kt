package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.cameraPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "camera_preferences"
)

class CameraPreferences(private val context: Context) {

    val depthBoostEnabled: Flow<Boolean> = context.cameraPrefsDataStore.data
        .map { prefs -> prefs[Keys.DEPTH_BOOST_ENABLED] ?: true }

    suspend fun setDepthBoostEnabled(enabled: Boolean) {
        context.cameraPrefsDataStore.edit { prefs ->
            prefs[Keys.DEPTH_BOOST_ENABLED] = enabled
        }
    }

    private object Keys {
        val DEPTH_BOOST_ENABLED = booleanPreferencesKey("depth_boost_enabled")
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _pixelDepthBoostEnabled = MutableStateFlow(true)
        val pixelDepthBoostEnabled: StateFlow<Boolean> = _pixelDepthBoostEnabled.asStateFlow()

        private val _isArCoreSupported = MutableStateFlow<Boolean?>(null)
        val isArCoreSupported: StateFlow<Boolean?> = _isArCoreSupported.asStateFlow()

        @Volatile
        private var initialized = false

        fun init(context: Context) {
            if (initialized) return
            initialized = true
            val appCtx = context.applicationContext
            scope.launch {
                appCtx.cameraPrefsDataStore.data
                    .map { prefs -> prefs[Keys.DEPTH_BOOST_ENABLED] ?: true }
                    .collect { _pixelDepthBoostEnabled.value = it }
            }
        }

        fun setPixelDepthBoostEnabled(context: Context, enabled: Boolean) {
            init(context)
            _pixelDepthBoostEnabled.value = enabled
            val appCtx = context.applicationContext
            scope.launch {
                appCtx.cameraPrefsDataStore.edit { prefs ->
                    prefs[Keys.DEPTH_BOOST_ENABLED] = enabled
                }
            }
        }

        fun setArCoreSupported(supported: Boolean) {
            _isArCoreSupported.value = supported
        }
    }
}
