package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.capture.CaptureViewModel
import com.example.capture.MotionSensorMonitor
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SpatialMint
import com.example.viewer.SpatialParallaxSurfaceView

/**
 * Post-capture review screen showing the captured 3D parallax photo with Save or Retake actions,
 * adapting between bottom bar (portrait) and trailing side bar (landscape).
 */
@Composable
fun ReviewPhotoScreen(
    viewModel: CaptureViewModel,
    onRetake: () -> Unit,
    onSaved: (photoId: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val staged by viewModel.stagedCapture.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val reviewSensorMonitor = remember(context) { MotionSensorMonitor(context) }
    val motion by reviewSensorMonitor.motion.collectAsStateWithLifecycle()

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var navigatedAway by remember { mutableStateOf(false) }

    val glSurfaceView = remember { SpatialParallaxSurfaceView(context) }

    fun performRetake() {
        if (navigatedAway || uiState.isSaving) return
        navigatedAway = true
        reviewSensorMonitor.stop()
        glSurfaceView.onPause()
        viewModel.resetForRetake()
        onRetake()
    }

    BackHandler(enabled = !uiState.isSaving) {
        performRetake()
    }

    DisposableEffect(lifecycleOwner) {
        fun resumePreview() {
            reviewSensorMonitor.recenterTilt()
            reviewSensorMonitor.start(includeGyro = false)
            glSurfaceView.onResume()
        }

        fun pausePreview() {
            reviewSensorMonitor.stop()
            glSurfaceView.onPause()
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumePreview()
                Lifecycle.Event.ON_PAUSE -> pausePreview()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resumePreview()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pausePreview()
        }
    }

    LaunchedEffect(staged) {
        val current = staged ?: return@LaunchedEffect
        glSurfaceView.setSpatialTextures(
            colorBitmap = current.heroBitmap,
            depthBitmap = current.depthResult.depthBitmap
        )
    }

    LaunchedEffect(motion.normTiltX, motion.normTiltY, dragOffsetX, dragOffsetY) {
        val combinedX = (motion.normTiltX + dragOffsetX).coerceIn(-1f, 1f)
        val combinedY = (motion.normTiltY + dragOffsetY).coerceIn(-1f, 1f)
        glSurfaceView.setTiltOffset(combinedX, combinedY)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        reviewSensorMonitor.recenterTilt()
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    },
                    onDragCancel = {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragOffsetX = (dragOffsetX + dragAmount.x / 200f).coerceIn(-1f, 1f)
                    dragOffsetY = (dragOffsetY + dragAmount.y / 200f).coerceIn(-1f, 1f)
                }
            }
    ) {
        val isLandscape = maxWidth > maxHeight

        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 14.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(50))
        ) {
            Text(
                text = stringResource(R.string.review_hint_tilt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (isLandscape) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .safeDrawingPadding()
                    .padding(end = 24.dp)
                    .width(160.dp)
            ) {
                SaveActionButton(
                    isSaving = uiState.isSaving,
                    enabled = !uiState.isSaving && staged != null && !navigatedAway,
                    onClick = {
                        viewModel.saveStagedCapture(onSaved = { id ->
                            navigatedAway = true
                            onSaved(id)
                        })
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                RetakeActionButton(
                    enabled = !uiState.isSaving && !navigatedAway,
                    onClick = ::performRetake,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                RetakeActionButton(
                    enabled = !uiState.isSaving && !navigatedAway,
                    onClick = ::performRetake,
                    modifier = Modifier.weight(1f)
                )
                SaveActionButton(
                    isSaving = uiState.isSaving,
                    enabled = !uiState.isSaving && staged != null && !navigatedAway,
                    onClick = {
                        viewModel.saveStagedCapture(onSaved = { id ->
                            navigatedAway = true
                            onSaved(id)
                        })
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RetakeActionButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
            .height(54.dp)
            .testTag("retake_button")
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.review_retake),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun SaveActionButton(
    isSaving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = SpatialMint,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = modifier
            .height(54.dp)
            .testTag("save_button")
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.review_saving),
                style = MaterialTheme.typography.labelLarge
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.review_save),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
