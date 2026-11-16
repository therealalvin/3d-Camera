package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import com.example.capture.CameraPreviewHost
import com.example.capture.CapturePhase
import com.example.capture.CaptureViewModel
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SpatialMint
import com.example.ui.theme.WarmAmber
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CaptureCameraScreen(
    onOpenGallery: () -> Unit,
    onCaptureReadyForReview: () -> Unit,
    viewModel: CaptureViewModel = viewModel()
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestThumb by viewModel.latestThumbnailPath.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                    viewModel.stopSensors()
                    viewModel.cancelSweepIfInterrupted()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopSensors()
        }
    }

    // Battery efficiency: only run capture motion sensors during an active SWEEPING capture
    DisposableEffect(hasCameraPermission, isResumed, uiState.phase) {
        if (hasCameraPermission && isResumed && uiState.phase == CapturePhase.SWEEPING) {
            viewModel.startSensors()
        } else {
            viewModel.stopSensors()
        }
        onDispose {
            viewModel.stopSensors()
        }
    }

    // Battery efficiency: keep display awake only while actively sweeping or fusing a capture
    val shouldKeepScreenOn = hasCameraPermission && isResumed &&
        (uiState.phase == CapturePhase.SWEEPING || uiState.phase == CapturePhase.PROCESSING)
    DisposableEffect(view, shouldKeepScreenOn) {
        view.keepScreenOn = shouldKeepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(uiState.clearedTilesCount) {
        if (uiState.clearedTilesCount > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
    ) {
        val isLandscape = maxWidth > maxHeight

        if (hasCameraPermission) {
            CameraPreviewHost(
                modifier = Modifier.fillMaxSize(),
                depthBoostEnabled = uiState.depthBoostEnabled,
                captureNextRequested = uiState.requestFrameGrab,
                onFrameAnalyzedPose = viewModel::onArPoseUpdated,
                onFrameCaptured = { frame ->
                    viewModel.onFrameCaptured(frame, onCaptureReadyForReview)
                },
                onArCoreSupportDetected = viewModel::onArCoreSupportDetected,
                onPipelineModeActive = viewModel::onPipelineModeActive
            )

            // Scrims tailored to portrait vs landscape orientation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isLandscape) 100.dp else 140.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(ObsidianBackground.copy(alpha = 0.65f), Color.Transparent)
                        )
                    )
            )
            if (isLandscape) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(160.dp)
                        .align(Alignment.CenterEnd)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, ObsidianBackground.copy(alpha = 0.85f))
                            )
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, ObsidianBackground.copy(alpha = 0.85f))
                            )
                        )
                )
            }

            // Guidance pill + optional Pixel Hardware Depth Boost pill (only on ARCore-capable devices)
            val guideText = when {
                uiState.phase == CapturePhase.PROCESSING -> stringResource(R.string.capture_processing)
                uiState.phase == CapturePhase.SWEEPING && uiState.isMovingTooFast ->
                    stringResource(R.string.capture_guide_steady)
                uiState.phase == CapturePhase.SWEEPING && uiState.isTiltOffLevel ->
                    stringResource(R.string.capture_guide_keep_tilt)
                uiState.phase == CapturePhase.SWEEPING ->
                    stringResource(R.string.capture_guide_sweep)
                else -> stringResource(R.string.capture_guide_ready)
            }
            val pillAccent = if (
                uiState.phase == CapturePhase.SWEEPING &&
                (uiState.isMovingTooFast || uiState.isTiltOffLevel)
            ) {
                WarmAmber
            } else {
                SpatialMint
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    modifier = Modifier.border(
                        1.dp,
                        pillAccent.copy(alpha = 0.4f),
                        RoundedCornerShape(50)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(pillAccent)
                        )
                        Text(
                            text = guideText,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Optional ARCore Toggle on Supported Devices (hidden if unsupported or sweeping)
                AnimatedVisibility(
                    visible = uiState.arCoreSupported && uiState.phase == CapturePhase.READY,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    DepthBoostTogglePill(
                        enabled = uiState.depthBoostEnabled,
                        onToggle = viewModel::toggleDepthBoost
                    )
                }
            }

            // Chalkboard-Erase 4x4 Detail Coverage Overlay while SWEEPING
            AnimatedVisibility(
                visible = uiState.phase == CapturePhase.SWEEPING,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(
                        start = 24.dp,
                        end = if (isLandscape) 128.dp else 24.dp,
                        top = if (isLandscape) 76.dp else 92.dp,
                        bottom = if (isLandscape) 76.dp else 184.dp
                    )
            ) {
                DetailCoverageOverlay(
                    tileDetails = uiState.tileDetails,
                    brushX = uiState.brushX,
                    brushY = uiState.brushY,
                    isWarning = uiState.isMovingTooFast || uiState.isTiltOffLevel
                )
            }

            // Bottom Hint / Detail Progress Pill
            val guideModifier = if (isLandscape) {
                Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 22.dp, end = 96.dp)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 128.dp)
            }

            AnimatedVisibility(
                visible = uiState.phase == CapturePhase.READY,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = guideModifier
            ) {
                ReadyWaveHintChip()
            }

            AnimatedVisibility(
                visible = uiState.phase == CapturePhase.SWEEPING,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = guideModifier
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    DetailProgressPill(
                        detailPercent = uiState.detailPercent,
                        clearedTiles = uiState.clearedTilesCount,
                        totalTiles = CaptureViewModel.TILE_COUNT,
                        isWarning = uiState.isMovingTooFast
                    )
                    TiltLevelMeterPill(
                        tiltX = uiState.tiltMeterX,
                        tiltY = uiState.tiltMeterY,
                        isOffLevel = uiState.isTiltOffLevel
                    )
                }
            }

            // Adaptive Studio Controls: Bottom Bar in Portrait, Right Rail in Landscape
            if (isLandscape) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                        .safeDrawingPadding()
                        .padding(end = 20.dp, top = 24.dp, bottom = 24.dp)
                ) {
                    ShutterCaptureButton(
                        phase = uiState.phase,
                        onClick = { viewModel.onShutterPressed(onCaptureReadyForReview = onCaptureReadyForReview) },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("shutter_button")
                    )

                    GalleryThumbnailButton(
                        latestThumbPath = latestThumb,
                        enabled = uiState.phase != CapturePhase.PROCESSING,
                        onClick = onOpenGallery,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .testTag("gallery_thumbnail_button")
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(horizontal = 28.dp, vertical = 24.dp)
                ) {
                    GalleryThumbnailButton(
                        latestThumbPath = latestThumb,
                        enabled = uiState.phase != CapturePhase.PROCESSING,
                        onClick = onOpenGallery,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .testTag("gallery_thumbnail_button")
                    )

                    ShutterCaptureButton(
                        phase = uiState.phase,
                        onClick = { viewModel.onShutterPressed(onCaptureReadyForReview = onCaptureReadyForReview) },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .testTag("shutter_button")
                    )
                }
            }

            // Processing overlay
            AnimatedVisibility(
                visible = uiState.phase == CapturePhase.PROCESSING,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ObsidianBackground.copy(alpha = 0.72f))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = SpatialMint,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = stringResource(R.string.capture_processing),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        } else {
            CameraPermissionGate(
                onAllowClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenGallery = onOpenGallery
            )
        }
    }
}

@Composable
private fun DepthBoostTogglePill(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = stringResource(if (enabled) R.string.depth_boost_on else R.string.depth_boost_off)
    val cd = stringResource(R.string.cd_toggle_depth_boost)
    val accent = if (enabled) SpatialMint else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(50),
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            .border(
                1.dp,
                if (enabled) SpatialMint.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(50)
            )
            .clickable(onClick = onToggle)
            .semantics { contentDescription = cd }
            .testTag("depth_boost_toggle")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) SpatialMint else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GalleryThumbnailButton(
    latestThumbPath: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(56.dp)
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        if (!latestThumbPath.isNullOrBlank() && File(latestThumbPath).exists()) {
            AsyncImage(
                model = File(latestThumbPath),
                contentDescription = stringResource(R.string.cd_open_gallery),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Collections,
                contentDescription = stringResource(R.string.cd_open_gallery),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ShutterCaptureButton(
    phase: CapturePhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val innerSize by animateDpAsState(
        targetValue = if (phase == CapturePhase.SWEEPING) 32.dp else 62.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "shutterInnerSize"
    )
    val innerCorner by animateDpAsState(
        targetValue = if (phase == CapturePhase.SWEEPING) 8.dp else 31.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "shutterInnerCorner"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(82.dp)
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .border(
                width = 4.dp,
                color = if (phase == CapturePhase.SWEEPING) SpatialMint else MaterialTheme.colorScheme.onBackground,
                shape = CircleShape
            )
            .clickable(enabled = phase != CapturePhase.PROCESSING, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(
                    if (phase == CapturePhase.SWEEPING) SpatialMint else MaterialTheme.colorScheme.onBackground
                )
        ) {
            if (phase == CapturePhase.SWEEPING) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.cd_shutter_stop),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ReadyWaveHintChip(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "readyWaveHint")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing)
        ),
        label = "phoneWavePhase"
    )
    // Gentle figure-8 "chalkboard erase" motion
    val ox = (sin(wavePhase.toDouble()) * 5.0).roundToInt()
    val oy = (sin((wavePhase * 2f).toDouble()) * 2.5).roundToInt()

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f), RoundedCornerShape(50))
            .testTag("ready_wave_hint")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Smartphone,
                contentDescription = null,
                tint = SpatialMint,
                modifier = Modifier
                    .size(16.dp)
                    .offset { IntOffset(ox, oy) }
            )
            Text(
                text = stringResource(R.string.capture_hint_keep_upright),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun DetailCoverageOverlay(
    tileDetails: List<Float>,
    brushX: Float,
    brushY: Float,
    isWarning: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = if (isWarning) WarmAmber else SpatialMint
    val pulseTransition = rememberInfiniteTransition(label = "mistTilePulse")
    val mistPulse by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mistPulseAlpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("detail_coverage_grid")
    ) {
        val cols = CaptureViewModel.GRID_COLS
        val rows = CaptureViewModel.GRID_ROWS
        val gap = 8.dp.toPx()
        val cellW = (size.width - gap * (cols - 1)) / cols
        val cellH = (size.height - gap * (rows - 1)) / rows
        val corner = CornerRadius(12.dp.toPx(), 12.dp.toPx())

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val detail = tileDetails.getOrElse(idx) { 0f }.coerceIn(0f, 1f)
                val isDone = detail >= CaptureViewModel.TILE_DONE_THRESHOLD
                val topLeft = Offset(c * (cellW + gap), r * (cellH + gap))
                val cellSize = Size(cellW, cellH)
                val cellCenter = Offset(topLeft.x + cellW / 2f, topLeft.y + cellH / 2f)

                if (isDone) {
                    // Wiped clean: let live camera show cleanly with a crisp faint mint frame
                    drawRoundRect(
                        color = SpatialMint.copy(alpha = 0.28f),
                        topLeft = topLeft,
                        size = cellSize,
                        cornerRadius = corner,
                        style = Stroke(width = 1.dp.toPx())
                    )
                } else {
                    // Needs detail: soft chalkboard mist that clears as detail approaches 80%+
                    val remaining = 1f - (detail / CaptureViewModel.TILE_DONE_THRESHOLD)
                    drawRoundRect(
                        color = ObsidianBackground.copy(alpha = 0.42f * remaining),
                        topLeft = topLeft,
                        size = cellSize,
                        cornerRadius = corner
                    )
                    drawRoundRect(
                        color = (if (detail > 0.25f) SpatialMint else Color.White)
                            .copy(alpha = 0.28f + 0.16f * remaining),
                        topLeft = topLeft,
                        size = cellSize,
                        cornerRadius = corner,
                        style = Stroke(width = 1.25.dp.toPx())
                    )
                    // Subtle center dot highlighting unfinished regions
                    drawCircle(
                        color = Color.White.copy(alpha = mistPulse * 0.55f * remaining),
                        radius = 4.dp.toPx(),
                        center = cellCenter
                    )
                }
            }
        }

        // Live chalkboard eraser brush halo following your hand motion
        val bx = ((brushX.coerceIn(-1f, 1f) * 0.5f + 0.5f) * size.width)
        val by = ((brushY.coerceIn(-1f, 1f) * 0.5f + 0.5f) * size.height)
        val brushPos = Offset(bx, by)
        drawCircle(
            color = accent.copy(alpha = 0.22f),
            radius = 24.dp.toPx(),
            center = brushPos
        )
        drawCircle(
            color = accent.copy(alpha = 0.75f),
            radius = 24.dp.toPx(),
            center = brushPos,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = Color.White,
            radius = 5.dp.toPx(),
            center = brushPos
        )
    }
}

@Composable
private fun DetailProgressPill(
    detailPercent: Int,
    clearedTiles: Int,
    totalTiles: Int,
    isWarning: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isWarning) WarmAmber else SpatialMint

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        modifier = modifier
            .border(1.dp, activeColor.copy(alpha = 0.5f), RoundedCornerShape(50))
            .testTag("detail_progress_pill")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = activeColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.capture_step_progress, detailPercent, clearedTiles, totalTiles),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun TiltLevelMeterPill(
    tiltX: Float,
    tiltY: Float,
    isOffLevel: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = if (isOffLevel) WarmAmber else SpatialMint
    val levelDesc = stringResource(if (isOffLevel) R.string.tilt_meter_adjust else R.string.tilt_meter_level)

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        modifier = modifier
            .border(1.dp, activeColor.copy(alpha = 0.55f), RoundedCornerShape(50))
            .semantics(mergeDescendants = true) { contentDescription = levelDesc }
            .testTag("tilt_level_meter")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Canvas(modifier = Modifier.size(26.dp)) {
                val r = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                // Outer ring + target sweet-spot circle + subtle crosshairs
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = r - 1.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.2f),
                    start = Offset(center.x - r + 2.dp.toPx(), center.y),
                    end = Offset(center.x + r - 2.dp.toPx(), center.y),
                    strokeWidth = 0.8.dp.toPx()
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.2f),
                    start = Offset(center.x, center.y - r + 2.dp.toPx()),
                    end = Offset(center.x, center.y + r - 2.dp.toPx()),
                    strokeWidth = 0.8.dp.toPx()
                )
                // Inner sweet-spot circle matches exact ~3.2° tilt gate out of 5.5° full travel
                val maxTravel = r - 4.5.dp.toPx()
                val sweetSpotRadius = maxTravel * (3.2f / 5.5f)
                drawCircle(
                    color = activeColor.copy(alpha = 0.72f),
                    radius = sweetSpotRadius,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx())
                )
                // Clamped live spirit-level bubble
                val clampedDist = kotlin.math.hypot(tiltX, tiltY)
                val scale = if (clampedDist > 1f) 1f / clampedDist else 1f
                val bx = center.x + tiltX * scale * maxTravel
                val by = center.y + tiltY * scale * maxTravel
                drawCircle(
                    color = activeColor,
                    radius = 3.8.dp.toPx(),
                    center = Offset(bx, by)
                )
            }
            Text(
                text = levelDesc,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = activeColor
            )
        }
    }
}

@Composable
private fun CameraPermissionGate(
    onAllowClick: () -> Unit,
    onOpenGallery: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(28.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = SpatialMint,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.camera_permission_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.camera_permission_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onAllowClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpatialMint,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("allow_camera_button")
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_button),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Button(
                    onClick = onOpenGallery,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("permission_open_gallery_button")
                ) {
                    Text(
                        text = stringResource(R.string.gallery_title),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
