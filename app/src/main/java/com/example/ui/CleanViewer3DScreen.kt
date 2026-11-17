package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.theme.ErrorCrimson
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SpatialMint
import com.example.viewer.SpatialParallaxSurfaceView
import com.example.viewer.ViewerUiState
import com.example.viewer.ViewerViewModel
import com.example.viewer.rememberSpatialViewportState
import com.example.viewer.spatialLookAndZoomGestures

@Composable
fun CleanViewer3DScreen(
    photoId: String?,
    externalUri: String?,
    onBack: () -> Unit,
    onOpenGallery: () -> Unit = {},
    viewModel: ViewerViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val viewportState = rememberSpatialViewportState()

    var showChrome by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val vrSavedMsg = stringResource(R.string.viewer_vr_saved)

    LaunchedEffect(photoId, externalUri) {
        viewportState.reset()
        viewModel.load(photoId, externalUri)
    }

    val glSurfaceView = remember { SpatialParallaxSurfaceView(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glSurfaceView.onResume()
                Lifecycle.Event.ON_PAUSE -> glSurfaceView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            glSurfaceView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glSurfaceView.onPause()
        }
    }

    // Feed touch-drag look-around and pinch zoom/pan into OpenGL ES parallax renderer
    LaunchedEffect(viewportState.lookX, viewportState.lookY) {
        glSurfaceView.setTiltOffset(viewportState.lookX, viewportState.lookY)
    }

    LaunchedEffect(viewportState.zoom, viewportState.panX, viewportState.panY) {
        glSurfaceView.setZoomAndPan(viewportState.zoom, viewportState.panX, viewportState.panY)
    }

    val readyState = uiState as? ViewerUiState.Ready
    LaunchedEffect(readyState?.vrSavedSuccess) {
        if (readyState?.vrSavedSuccess == true) {
            snackbarHostState.showSnackbar(vrSavedMsg)
            viewModel.clearVrStatus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .spatialLookAndZoomGestures(
                state = viewportState,
                onSingleTap = { showChrome = !showChrome }
            )
    ) {
        when (val state = uiState) {
            ViewerUiState.Loading -> {
                CircularProgressIndicator(
                    color = SpatialMint,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is ViewerUiState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Button(onClick = onBack) {
                        Text(stringResource(R.string.cd_back))
                    }
                }
            }

            is ViewerUiState.Ready -> {
                LaunchedEffect(state.photo.decoded.colorBitmap, state.photo.decoded.depthBitmap) {
                    glSurfaceView.setSpatialTextures(
                        colorBitmap = state.photo.decoded.colorBitmap,
                        depthBitmap = state.photo.decoded.depthBitmap
                    )
                }

                AndroidView(
                    factory = { glSurfaceView },
                    modifier = Modifier.fillMaxSize()
                )

                // Top floating bar: Back + Delete
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                                .testTag("viewer_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onOpenGallery,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                                    .testTag("viewer_gallery_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Collections,
                                    contentDescription = stringResource(R.string.cd_open_gallery),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (state.photo.entity != null) {
                                IconButton(
                                    onClick = { showDeleteDialog = true },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                                        .testTag("viewer_delete_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.cd_delete_photo),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom minimal pill: friendly tilt hint + optional Save 3D for VR
                AnimatedVisibility(
                    visible = showChrome,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                            modifier = Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(50)
                            )
                        ) {
                            Text(
                                text = if (state.photo.decoded.hasDepthMap) {
                                    stringResource(R.string.viewer_hint_tilt)
                                } else {
                                    stringResource(R.string.viewer_no_depth_notice)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        if (state.photo.decoded.hasDepthMap) {
                            Button(
                                onClick = { viewModel.exportQuestVrSbs() },
                                enabled = !state.isExportingVr,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SpatialMint,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("viewer_save_vr_button")
                            ) {
                                if (state.isExportingVr) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.ViewInAr,
                                        contentDescription = stringResource(R.string.cd_save_vr),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = stringResource(R.string.viewer_save_vr),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 12.dp)
        )

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(stringResource(R.string.viewer_delete_title)) },
                text = { Text(stringResource(R.string.viewer_delete_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteCurrentPhoto(onDeleted = onBack)
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = ErrorCrimson
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}
