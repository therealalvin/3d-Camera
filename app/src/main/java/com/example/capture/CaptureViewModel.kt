package com.example.capture

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.CameraPreferences
import com.example.data.CapturedFrameData
import com.example.data.SpatialMediaRepository
import com.example.imaging.DynamicDepthCodec
import com.example.imaging.MetricDepthObservation
import com.example.imaging.RectifiedFrameResult
import com.example.imaging.RefinedDepthResult
import com.example.imaging.StereoNeighborObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CapturePhase {
    READY,
    SWEEPING,
    PROCESSING
}

enum class CaptureQualityTier {
    POOR,
    FAIR,
    GOOD,
    EXCELLENT
}

enum class FrameComparisonStatus {
    NONE,
    BETTER,
    WORSE
}

data class StagedCapture(
    val heroBitmap: Bitmap,
    val depthResult: RefinedDepthResult,
    val sweepFrames: List<CapturedFrameData>
)

data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.READY,
    val tileDetails: List<Float> = List(CaptureViewModel.TILE_COUNT) { 0f },
    val tileQualities: List<Float> = List(CaptureViewModel.TILE_COUNT) { 0f },
    val weakestAreaIndex: Int? = null,
    val lastFrameComparison: FrameComparisonStatus = FrameComparisonStatus.NONE,
    val clearedTilesCount: Int = 0,
    val detailPercent: Int = 0,
    val capturedFrameCount: Int = 0,
    val qualityScore: Float = 0f,
    val qualityTier: CaptureQualityTier = CaptureQualityTier.POOR,
    val canAcceptCapture: Boolean = false,
    val brushX: Float = 0f,
    val brushY: Float = 0f,
    val tiltMeterX: Float = 0f,
    val tiltMeterY: Float = 0f,
    val isTiltOffLevel: Boolean = false,
    val isMovingTooFast: Boolean = false,
    val isTrackingReady: Boolean = true,
    val requestFrameGrab: Boolean = false,
    val arCoreSupported: Boolean = false,
    val depthBoostEnabled: Boolean = true,
    val isArCoreModeActive: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

private data class StagedFrame(
    val colorBitmap: Bitmap,
    val depthBuffer: Depth16Buffer?,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val aimYawDeg: Float,
    val aimUpDeg: Float,
    val rollRightDeg: Float,
    val nx: Float,
    val ny: Float,
    val gyroMag: Float,
    val tiltDeg: Float,
    val frameQuality: Float,
    val patchQualities: FloatArray
)

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).spatialPhotoDao()
    private val repository = SpatialMediaRepository(application, dao)
    val sensorMonitor = MotionSensorMonitor(application)

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private val _stagedCapture = MutableStateFlow<StagedCapture?>(null)
    val stagedCapture: StateFlow<StagedCapture?> = _stagedCapture.asStateFlow()

    val latestThumbnailPath: StateFlow<String?> = repository.allPhotos
        .map { it.firstOrNull()?.previewImagePath }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val framePool = mutableListOf<StagedFrame>()
    private val tileProgress = FloatArray(TILE_COUNT)
    private val bestTileQuality = FloatArray(TILE_COUNT)

    private var sensorJob: Job? = null
    private var originArPose: LivePoseSnapshot? = null
    private var originAimYawDeg: Float? = null
    private var originAimUpDeg: Float? = null

    private var currentNx = 0f
    private var currentNy = 0f
    private var lastBrushNx = 0f
    private var lastBrushNy = 0f
    private var minNx = 0f
    private var maxNx = 0f
    private var minNy = 0f
    private var maxNy = 0f

    private var grabInFlight = false
    private var pendingGrabNx = 0f
    private var pendingGrabNy = 0f
    private var lastGrabAtMs = 0L
    private var completionTriggered = false

    init {
        CameraPreferences.init(application)
        viewModelScope.launch {
            CameraPreferences.pixelDepthBoostEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(depthBoostEnabled = enabled)
            }
        }
    }

    fun startSensors() {
        sensorJob?.cancel()
        sensorMonitor.start(includeGyro = true)
        sensorJob = viewModelScope.launch {
            sensorMonitor.motion.collect { motion ->
                val state = _uiState.value
                val movingFast = motion.gyroMagnitudeRadPerSec > 1.25f
                val activeTiltDeg = if (state.isArCoreModeActive) motion.tiltDeviationDeg else motion.rollDeviationDeg
                val offTilt = activeTiltDeg > TILT_LEVEL_TOLERANCE_DEG
                if (state.isMovingTooFast != movingFast ||
                    state.isTiltOffLevel != offTilt ||
                    abs(motion.tiltOffsetX - state.tiltMeterX) >= 0.02f ||
                    abs(motion.tiltOffsetY - state.tiltMeterY) >= 0.02f
                ) {
                    _uiState.value = state.copy(
                        isMovingTooFast = movingFast,
                        isTiltOffLevel = offTilt,
                        tiltMeterX = motion.tiltOffsetX,
                        tiltMeterY = motion.tiltOffsetY
                    )
                }

                if (state.phase == CapturePhase.SWEEPING && !state.isArCoreModeActive) {
                    // Camera aim is already relative to the gimbal-lock-free basis locked at beginSweep()
                    processWaveMotionSample(
                        nxRaw = motion.aimYawDeg / WAVE_HALF_SPAN_DEG,
                        nyRaw = -motion.aimUpDeg / WAVE_HALF_SPAN_DEG,
                        tileDepthDensities = null,
                        motion = motion
                    )
                }
            }
        }
    }

    fun stopSensors() {
        sensorJob?.cancel()
        sensorJob = null
        sensorMonitor.stop()
    }

    fun cancelSweepIfInterrupted() {
        if (_uiState.value.phase == CapturePhase.SWEEPING) {
            cancelSweep()
        }
    }

    fun onArCoreSupportDetected(isSupported: Boolean) {
        CameraPreferences.setArCoreSupported(isSupported)
        _uiState.value = _uiState.value.copy(arCoreSupported = isSupported)
    }

    fun onPipelineModeActive(isArCoreActive: Boolean) {
        _uiState.value = _uiState.value.copy(isArCoreModeActive = isArCoreActive)
    }

    fun toggleDepthBoost() {
        val current = _uiState.value
        if (!current.arCoreSupported || current.phase != CapturePhase.READY) return
        val next = !current.depthBoostEnabled
        _uiState.value = current.copy(depthBoostEnabled = next)
        CameraPreferences.setPixelDepthBoostEnabled(getApplication(), next)
    }

    fun onShutterPressed(onCaptureReadyForReview: () -> Unit) {
        when (_uiState.value.phase) {
            CapturePhase.READY -> beginSweep()
            CapturePhase.SWEEPING -> {
                if (canAcceptFrames()) {
                    finalizeSweepForReview(onCaptureReadyForReview)
                } else {
                    cancelSweep()
                }
            }
            CapturePhase.PROCESSING -> Unit
        }
    }

    fun onAcceptCapturePressed(onCaptureReadyForReview: () -> Unit) {
        if (_uiState.value.phase != CapturePhase.SWEEPING || completionTriggered) return
        if (!canAcceptFrames()) return
        finalizeSweepForReview(onCaptureReadyForReview)
    }

    fun onCancelCapturePressed() {
        if (_uiState.value.phase == CapturePhase.SWEEPING) {
            cancelSweep()
        }
    }

    private fun canAcceptFrames(): Boolean {
        return framePool.size >= MIN_FRAMES_TO_ACCEPT ||
            (_uiState.value.isArCoreModeActive && framePool.any { it.depthBuffer != null })
    }

    private fun resetWaveTracking() {
        originArPose = null
        originAimYawDeg = null
        originAimUpDeg = null
        currentNx = 0f
        currentNy = 0f
        lastBrushNx = 0f
        lastBrushNy = 0f
        minNx = 0f
        maxNx = 0f
        minNy = 0f
        maxNy = 0f
        tileProgress.fill(0f)
        bestTileQuality.fill(0f)
        grabInFlight = false
        pendingGrabNx = 0f
        pendingGrabNy = 0f
        lastGrabAtMs = 0L
        completionTriggered = false
    }

    private fun currentQualityState(): Pair<Float, CaptureQualityTier> {
        val avgTileDetail = tileProgress.sum() / TILE_COUNT
        val avgGyro = if (framePool.isNotEmpty()) {
            framePool.map { it.gyroMag }.average().toFloat()
        } else {
            0f
        }
        val avgFrameQual = if (framePool.isNotEmpty()) {
            framePool.map { it.frameQuality }.average().toFloat()
        } else {
            0.7f
        }
        val hasMetric = framePool.any { it.depthBuffer != null }
        val spanX = maxNx - minNx
        val spanY = maxNy - minNy
        return computeCaptureQualityScore(
            frameCount = framePool.size,
            spanX = spanX,
            spanY = spanY,
            avgTileDetail = avgTileDetail,
            avgGyroMag = avgGyro,
            hasMetricDepthFrames = hasMetric,
            avgFrameQuality = avgFrameQual
        )
    }

    private fun processWaveMotionSample(
        nxRaw: Float,
        nyRaw: Float,
        tileDepthDensities: FloatArray?,
        motion: MotionSample
    ) {
        if (completionTriggered) return
        val nx = nxRaw.coerceIn(-1f, 1f)
        val ny = nyRaw.coerceIn(-1f, 1f)
        val current = _uiState.value

        currentNx = nx
        currentNy = ny
        val stepDist = hypot(nx - lastBrushNx, ny - lastBrushNy)

        minNx = min(minNx, nx)
        maxNx = max(maxNx, nx)
        minNy = min(minNy, ny)
        maxNy = max(maxNy, ny)

        val spanX = maxNx - minNx
        val spanY = maxNy - minNy
        val gentleEnough = motion.gyroMagnitudeRadPerSec < MAX_CAPTURE_GYRO_RAD_PER_SEC
        val steadyWeight = (1f - (motion.gyroMagnitudeRadPerSec / (MAX_CAPTURE_GYRO_RAD_PER_SEC + 0.35f)))
            .coerceIn(0.35f, 1f)

        var tilesChanged = false
        val subSteps = if (stepDist >= 0.10f) 3 else 1
        for (s in 1..subSteps) {
            val t = s.toFloat() / subSteps
            val sx = lastBrushNx + (nx - lastBrushNx) * t
            val sy = lastBrushNy + (ny - lastBrushNy) * t
            val (sCol, sRow) = computeBrushGridPos(sx, sy, GRID_COLS, GRID_ROWS)
            val segDist = stepDist / subSteps

            for (r in 0 until GRID_ROWS) {
                for (c in 0 until GRID_COLS) {
                    val idx = r * GRID_COLS + c
                    val prev = tileProgress[idx]
                    val dCol = c - sCol
                    val dRow = r - sRow
                    val dSq = dCol * dCol + dRow * dRow
                    val eraserFalloff = exp(-dSq / 0.85f)

                    var nextVal = prev
                    if (gentleEnough && segDist >= 0.008f) {
                        nextVal = max(
                            nextVal,
                            (prev + eraserFalloff * segDist * 0.95f * steadyWeight).coerceAtMost(1f)
                        )
                    }
                    if (s == subSteps && tileDepthDensities != null && idx < tileDepthDensities.size &&
                        dSq <= 1.25f && (spanX + spanY) > 0.15f
                    ) {
                        val arBoost = (tileDepthDensities[idx] * 0.20f + eraserFalloff * 0.12f).coerceAtMost(0.32f)
                        nextVal = max(nextVal, arBoost)
                    }

                    if (abs(nextVal - prev) >= 0.02f) {
                        tileProgress[idx] = nextVal
                        tilesChanged = true
                    }
                }
            }
        }

        if (stepDist >= 0.015f) {
            lastBrushNx = nx
            lastBrushNy = ny
        }

        val brushMoved = abs(nx - current.brushX) >= 0.025f || abs(ny - current.brushY) >= 0.025f
        if (tilesChanged || brushMoved) {
            val cleared = tileProgress.count { it >= TILE_DONE_THRESHOLD }
            val avgDetail = (tileProgress.sum() / TILE_COUNT * 100f).roundToInt().coerceIn(0, 100)
            val (qScore, qTier) = currentQualityState()
            _uiState.value = _uiState.value.copy(
                tileDetails = tileProgress.toList(),
                clearedTilesCount = cleared,
                detailPercent = avgDetail,
                qualityScore = qScore,
                qualityTier = qTier,
                capturedFrameCount = framePool.size,
                canAcceptCapture = canAcceptFrames(),
                brushX = nx,
                brushY = ny
            )
        }

        // Capture freely as camera moves/pans/tilts whenever motion isn't violently blurry
        if (!grabInFlight && motion.gyroMagnitudeRadPerSec < MAX_CAPTURE_GYRO_RAD_PER_SEC) {
            val now = System.currentTimeMillis()
            val minExistingDist = framePool.minOfOrNull { hypot(nx - it.nx, ny - it.ny) } ?: Float.MAX_VALUE
            val minAngleDelta = framePool.minOfOrNull {
                val dy = MotionSensorMonitor.wrapAngleDelta(motion.aimYawDeg, it.aimYawDeg)
                val dp = MotionSensorMonitor.wrapAngleDelta(motion.aimUpDeg, it.aimUpDeg)
                val dr = MotionSensorMonitor.wrapAngleDelta(motion.rollRightDeg, it.rollRightDeg)
                hypot(hypot(dy, dp), dr)
            } ?: Float.MAX_VALUE

            val elapsed = now - lastGrabAtMs
            val earlyRamp = framePool.size < 6 && elapsed >= 170L && (minExistingDist >= 0.10f || minAngleDelta >= 2.0f)
            val newRegion = elapsed >= 160L && (minExistingDist >= 0.18f || minAngleDelta >= 3.2f)
            val upgradeProbe = elapsed >= 230L && motion.gyroMagnitudeRadPerSec < 1.15f

            if (earlyRamp || newRegion || upgradeProbe) {
                triggerFrameGrab(nx = nx, ny = ny)
            }
        }
    }

    private fun beginSweep() {
        clearFramePool()
        resetWaveTracking()
        sensorMonitor.recenterTilt()
        _uiState.value = _uiState.value.copy(
            phase = CapturePhase.SWEEPING,
            tileDetails = List(TILE_COUNT) { 0f },
            tileQualities = List(TILE_COUNT) { 0f },
            weakestAreaIndex = null,
            lastFrameComparison = FrameComparisonStatus.NONE,
            clearedTilesCount = 0,
            detailPercent = 0,
            capturedFrameCount = 0,
            qualityScore = 0f,
            qualityTier = CaptureQualityTier.POOR,
            canAcceptCapture = false,
            brushX = 0f,
            brushY = 0f,
            tiltMeterX = 0f,
            tiltMeterY = 0f,
            isTiltOffLevel = false,
            requestFrameGrab = false,
            errorMessage = null
        )
        // Capture initial centered frame immediately when user taps shutter
        triggerFrameGrab(nx = 0f, ny = 0f)
    }

    private fun cancelSweep() {
        clearFramePool()
        resetWaveTracking()
        _uiState.value = _uiState.value.copy(
            phase = CapturePhase.READY,
            tileDetails = List(TILE_COUNT) { 0f },
            tileQualities = List(TILE_COUNT) { 0f },
            weakestAreaIndex = null,
            lastFrameComparison = FrameComparisonStatus.NONE,
            clearedTilesCount = 0,
            detailPercent = 0,
            capturedFrameCount = 0,
            qualityScore = 0f,
            qualityTier = CaptureQualityTier.POOR,
            canAcceptCapture = false,
            brushX = 0f,
            brushY = 0f,
            tiltMeterX = 0f,
            tiltMeterY = 0f,
            isTiltOffLevel = false,
            requestFrameGrab = false
        )
    }

    fun onArPoseUpdated(pose: LivePoseSnapshot) {
        mainHandler.post {
            val state = _uiState.value
            if (state.isTrackingReady != pose.isTracking) {
                _uiState.value = state.copy(isTrackingReady = pose.isTracking)
            }
            if (state.phase != CapturePhase.SWEEPING || !state.isArCoreModeActive) return@post
            if (!pose.isTracking) return@post

            val origin = originArPose ?: pose.also { originArPose = it }
            val (dxRight, dyUp) = SmartCaptureHelpers.projectWorldDeltaToCameraRightUp(
                dwx = pose.tx - origin.tx,
                dwy = pose.ty - origin.ty,
                dwz = pose.tz - origin.tz,
                qx = origin.qx,
                qy = origin.qy,
                qz = origin.qz,
                qw = origin.qw
            )
            processWaveMotionSample(
                nxRaw = dxRight / WAVE_HALF_SPAN_METERS,
                nyRaw = -dyUp / WAVE_HALF_SPAN_METERS,
                tileDepthDensities = pose.tileDepthDensities,
                motion = sensorMonitor.motion.value
            )
        }
    }

    private fun triggerFrameGrab(nx: Float, ny: Float) {
        grabInFlight = true
        lastGrabAtMs = System.currentTimeMillis()
        pendingGrabNx = nx
        pendingGrabNy = ny
        _uiState.value = _uiState.value.copy(requestFrameGrab = true)
    }

    fun onFrameCaptured(frame: CapturedArFrame, onCaptureReadyForReview: () -> Unit) {
        mainHandler.post {
            val state = _uiState.value
            if (state.phase != CapturePhase.SWEEPING) {
                frame.colorBitmap.recycle()
                _uiState.value = state.copy(requestFrameGrab = false)
                grabInFlight = false
                return@post
            }

            val motion = sensorMonitor.motion.value
            val activeTiltDeg = if (state.isArCoreModeActive) motion.tiltDeviationDeg else motion.rollDeviationDeg
            grabInFlight = false
            _uiState.value = _uiState.value.copy(requestFrameGrab = false)

            // Reject extreme motion blur spikes once we already have a baseline frame
            if (motion.gyroMagnitudeRadPerSec > 1.75f && framePool.isNotEmpty()) {
                frame.colorBitmap.recycle()
                _uiState.value = _uiState.value.copy(
                    lastFrameComparison = FrameComparisonStatus.WORSE
                )
                return@post
            }

            val sharpness = SmartCaptureHelpers.measureBitmapSharpness(frame.colorBitmap)
            val depthCoverage = measureDepthCoverage(frame.depthBuffer)
            val candQuality = computeSingleFrameQualityMetric(
                sharpness = sharpness,
                gyroMag = motion.gyroMagnitudeRadPerSec,
                tiltDeg = activeTiltDeg,
                depthCoverage = depthCoverage
            )
            val patchSharp = SmartCaptureHelpers.measureGridPatchSharpness(frame.colorBitmap, GRID_COLS, GRID_ROWS)
            val patchDepth = frame.depthBuffer?.let {
                SmartCaptureHelpers.computeTileDepthDensities(it.millimeters, it.width, it.height, GRID_COLS, GRID_ROWS)
            }
            val steadyFactor = (1f / (1f + motion.gyroMagnitudeRadPerSec.coerceAtLeast(0f) * 2.0f)).coerceIn(0f, 1f)
            val levelFactor = (1f / (1f + activeTiltDeg.coerceAtLeast(0f) * 0.20f)).coerceIn(0f, 1f)
            val patchQualities = FloatArray(TILE_COUNT) { idx ->
                val depthPart = patchDepth?.get(idx) ?: 0.55f
                (0.45f * patchSharp[idx] + 0.33f * steadyFactor + 0.14f * levelFactor + 0.08f * depthPart)
                    .coerceIn(0f, 1f)
            }

            val candidate = StagedFrame(
                colorBitmap = frame.colorBitmap,
                depthBuffer = frame.depthBuffer,
                tx = frame.tx,
                ty = frame.ty,
                tz = frame.tz,
                qx = frame.qx,
                qy = frame.qy,
                qz = frame.qz,
                qw = frame.qw,
                aimYawDeg = motion.aimYawDeg,
                aimUpDeg = motion.aimUpDeg,
                rollRightDeg = motion.rollRightDeg,
                nx = pendingGrabNx,
                ny = pendingGrabNy,
                gyroMag = motion.gyroMagnitudeRadPerSec,
                tiltDeg = activeTiltDeg,
                frameQuality = candQuality,
                patchQualities = patchQualities
            )

            // Credit detail and local per-area quality right under this frame
            val (bCol, bRow) = computeBrushGridPos(candidate.nx, candidate.ny, GRID_COLS, GRID_ROWS)
            var improvedExistingTile = false
            var aimedOverVisitedTile = false
            for (r in 0 until GRID_ROWS) {
                for (c in 0 until GRID_COLS) {
                    val idx = r * GRID_COLS + c
                    val dSq = (c - bCol) * (c - bCol) + (r - bRow) * (r - bRow)
                    if (dSq <= 1.05f) {
                        val boost = exp(-dSq / 0.85f) * 0.42f
                        tileProgress[idx] = max(tileProgress[idx], (tileProgress[idx] + boost).coerceAtMost(1f))
                    }
                    val aimWeight = exp(-dSq / 1.15f).coerceIn(0.32f, 1f)
                    val tileContrib = (patchQualities[idx] * aimWeight).coerceIn(0f, 1f)
                    val prevTileQ = bestTileQuality[idx]
                    if (prevTileQ > 0.12f && dSq <= 0.65f) {
                        aimedOverVisitedTile = true
                    }
                    if (prevTileQ > 0.08f && tileContrib > prevTileQ + 0.025f) {
                        improvedExistingTile = true
                    }
                    if (tileContrib > prevTileQ) {
                        bestTileQuality[idx] = tileContrib
                    }
                }
            }
            val nearestPrev = framePool
                .filter { hypot(candidate.nx - it.nx, candidate.ny - it.ny) in 0.12f..0.80f }
                .minByOrNull { hypot(candidate.nx - it.nx, candidate.ny - it.ny) }
            if (nearestPrev != null) {
                val (mCol, mRow) = computeBrushGridPos(
                    nx = (candidate.nx + nearestPrev.nx) * 0.5f,
                    ny = (candidate.ny + nearestPrev.ny) * 0.5f,
                    cols = GRID_COLS,
                    rows = GRID_ROWS
                )
                for (r in 0 until GRID_ROWS) {
                    for (c in 0 until GRID_COLS) {
                        val idx = r * GRID_COLS + c
                        val dSq = (c - mCol) * (c - mCol) + (r - mRow) * (r - mRow)
                        if (dSq <= 0.70f) {
                            val bridgeBoost = exp(-dSq / 0.65f) * 0.24f
                            tileProgress[idx] = max(tileProgress[idx], (tileProgress[idx] + bridgeBoost).coerceAtMost(1f))
                        }
                    }
                }
            }

            // Always prefer better-quality frames & retain frames that own crispest local patches for stacking
            var closestIdx = -1
            var closestNormDist = Float.MAX_VALUE
            for (i in framePool.indices) {
                val existing = framePool[i]
                val posDist = hypot(candidate.nx - existing.nx, candidate.ny - existing.ny)
                val dy = MotionSensorMonitor.wrapAngleDelta(candidate.aimYawDeg, existing.aimYawDeg)
                val dp = MotionSensorMonitor.wrapAngleDelta(candidate.aimUpDeg, existing.aimUpDeg)
                val angDist = hypot(dy, dp) / 14f
                val combined = posDist + angDist * 0.5f
                if (combined < closestNormDist) {
                    closestNormDist = combined
                    closestIdx = i
                }
            }

            val prevAvgQual = if (framePool.isNotEmpty()) {
                framePool.map { it.frameQuality }.average().toFloat()
            } else {
                0f
            }

            var comparisonStatus = FrameComparisonStatus.NONE
            if (closestIdx >= 0 && closestNormDist < SAME_VIEWPOINT_THRESHOLD) {
                val prev = framePool[closestIdx]
                if (shouldReplaceWithBetterFrame(candidate.frameQuality, prev.frameQuality)) {
                    prev.colorBitmap.recycle()
                    framePool[closestIdx] = candidate
                    comparisonStatus = FrameComparisonStatus.BETTER
                } else {
                    // Even if overall score didn't beat prev, keep candidate if pool has space & it owns a stronger patch
                    val ownsBetterPatch = (0 until TILE_COUNT).any { t ->
                        candidate.patchQualities[t] > prev.patchQualities[t] + 0.08f
                    }
                    if (ownsBetterPatch && framePool.size < MAX_FRAME_POOL) {
                        framePool.add(candidate)
                        comparisonStatus = FrameComparisonStatus.BETTER
                    } else {
                        candidate.colorBitmap.recycle()
                        comparisonStatus = if (improvedExistingTile) {
                            FrameComparisonStatus.BETTER
                        } else {
                            FrameComparisonStatus.WORSE
                        }
                    }
                }
            } else if (framePool.size < MAX_FRAME_POOL) {
                val hadBaseline = framePool.isNotEmpty()
                framePool.add(candidate)
                if (hadBaseline) {
                    comparisonStatus = when {
                        improvedExistingTile || candidate.frameQuality >= prevAvgQual * 0.96f -> FrameComparisonStatus.BETTER
                        aimedOverVisitedTile || candidate.frameQuality < prevAvgQual * 0.85f -> FrameComparisonStatus.WORSE
                        else -> FrameComparisonStatus.BETTER
                    }
                }
            } else {
                // Protect frames that are the top patch donor for any tile
                val bestDonorForTile = IntArray(TILE_COUNT) { t ->
                    framePool.indices.maxByOrNull { framePool[it].patchQualities[t] } ?: -1
                }
                val replaceIdx = if (closestIdx >= 0 && closestNormDist < 0.45f) {
                    closestIdx
                } else {
                    framePool.indices.minByOrNull { i ->
                        val uniquePatches = bestDonorForTile.count { it == i }
                        framePool[i].frameQuality + uniquePatches * 0.25f
                    } ?: -1
                }
                if (replaceIdx >= 0 && shouldReplaceWithBetterFrame(candidate.frameQuality, framePool[replaceIdx].frameQuality)) {
                    framePool[replaceIdx].colorBitmap.recycle()
                    framePool[replaceIdx] = candidate
                    comparisonStatus = FrameComparisonStatus.BETTER
                } else {
                    candidate.colorBitmap.recycle()
                    comparisonStatus = if (improvedExistingTile) FrameComparisonStatus.BETTER else FrameComparisonStatus.WORSE
                }
            }

            val cleared = tileProgress.count { it >= TILE_DONE_THRESHOLD }
            val avgDetail = (tileProgress.sum() / TILE_COUNT * 100f).roundToInt().coerceIn(0, 100)
            val (qScore, qTier) = currentQualityState()
            val tileQualList = bestTileQuality.toList()
            _uiState.value = _uiState.value.copy(
                tileDetails = tileProgress.toList(),
                tileQualities = tileQualList,
                weakestAreaIndex = computeWeakestAreaIndex(tileQualList),
                lastFrameComparison = comparisonStatus,
                clearedTilesCount = cleared,
                detailPercent = avgDetail,
                qualityScore = qScore,
                qualityTier = qTier,
                capturedFrameCount = framePool.size,
                canAcceptCapture = canAcceptFrames()
            )
            // No auto-finish: user controls exact moment via Accept 3D button
        }
    }

    private fun finalizeSweepForReview(onReadyForReview: () -> Unit) {
        if (framePool.isEmpty()) {
            cancelSweep()
            return
        }
        val framesSnapshot = framePool.toList()
        framePool.clear()
        completionTriggered = true

        _uiState.value = _uiState.value.copy(
            phase = CapturePhase.PROCESSING,
            requestFrameGrab = false
        )

        viewModelScope.launch {
            try {
                val staged = withContext(Dispatchers.Default) {
                    // Pick the single sharpest, centered, highest-quality frame as the Hero optical reference
                    val hasAnyMetricDepth = framesSnapshot.any { it.depthBuffer != null }
                    val hero = framesSnapshot.maxByOrNull { f ->
                        val centerScore = 1f / (1f + hypot(f.nx, f.ny) * 1.4f)
                        val depthBonus = if (!hasAnyMetricDepth || f.depthBuffer != null) 1.25f else 0.7f
                        (f.frameQuality + 0.15f) * centerScore * depthBonus
                    } ?: framesSnapshot.first()

                    data class AlignedNeighbor(
                        val source: StagedFrame,
                        val pose: RelativeFramePose,
                        val rectified: RectifiedFrameResult
                    )

                    val alignedNeighbors = mutableListOf<AlignedNeighbor>()
                    try {
                        for (f in framesSnapshot) {
                            if (f === hero) continue
                            val relPose = SmartCaptureHelpers.computeRelativePoseToHero(
                                heroQx = hero.qx, heroQy = hero.qy, heroQz = hero.qz, heroQw = hero.qw,
                                frameQx = f.qx, frameQy = f.qy, frameQz = f.qz, frameQw = f.qw,
                                heroAimYawDeg = hero.aimYawDeg,
                                heroAimUpDeg = hero.aimUpDeg,
                                heroRollRightDeg = hero.rollRightDeg,
                                frameAimYawDeg = f.aimYawDeg,
                                frameAimUpDeg = f.aimUpDeg,
                                frameRollRightDeg = f.rollRightDeg
                            )
                            val rect = DynamicDepthCodec.rectifyFrameToHeroPlane(
                                heroBitmap = hero.colorBitmap,
                                sourceBitmap = f.colorBitmap,
                                relativePose = relPose
                            )
                            alignedNeighbors.add(AlignedNeighbor(f, relPose, rect))
                        }

                        val goodAligned = alignedNeighbors
                            .filter { it.rectified.edgeMatchScore >= 0.30f }
                            .ifEmpty { alignedNeighbors }

                        val enhancedHero = DynamicDepthCodec.enhanceHeroWithAlignedFrames(
                            heroBitmap = hero.colorBitmap,
                            rectifiedFrames = goodAligned
                                .sortedByDescending { it.rectified.edgeMatchScore / (1f + hypot(it.source.nx - hero.nx, it.source.ny - hero.ny)) }
                                .map { it.rectified }
                        )

                        val heroW = max(1, hero.colorBitmap.width).toFloat()
                        val heroH = max(1, hero.colorBitmap.height).toFloat()

                        val stereoNeighbors = goodAligned.map { item ->
                            val f = item.source
                            val (dxRight, dyUp) = SmartCaptureHelpers.projectWorldDeltaToCameraRightUp(
                                dwx = f.tx - hero.tx,
                                dwy = f.ty - hero.ty,
                                dwz = f.tz - hero.tz,
                                qx = hero.qx,
                                qy = hero.qy,
                                qz = hero.qz,
                                qw = hero.qw
                            )
                            val effDx = if (abs(dxRight) > 0.004f) dxRight else (f.nx - hero.nx) * WAVE_HALF_SPAN_METERS
                            val effDy = if (abs(dyUp) > 0.004f) dyUp else -(f.ny - hero.ny) * WAVE_HALF_SPAN_METERS
                            StereoNeighborObservation(
                                bitmap = item.rectified.bitmap,
                                dxRight = effDx,
                                dyUp = effDy,
                                weight = (item.rectified.edgeMatchScore * 1.25f) / (1f + f.gyroMag * 0.8f)
                            )
                        }

                        val alignedMap = alignedNeighbors.associateBy { it.source }
                        val metricObservations = framesSnapshot.mapNotNull { f ->
                            val db = f.depthBuffer ?: return@mapNotNull null
                            val distFromHero = hypot(f.nx - hero.nx, f.ny - hero.ny)
                            val alignedItem = alignedMap[f]
                            val warpedDepth = if (alignedItem != null) {
                                DynamicDepthCodec.rectifyDepth16ToHero(
                                    depthBuffer = db,
                                    relRollDeg = alignedItem.pose.relRollDeg,
                                    residualDxRatio = alignedItem.rectified.residualDxPx / heroW,
                                    residualDyRatio = alignedItem.rectified.residualDyPx / heroH
                                )
                            } else {
                                db
                            }
                            val edgeFactor = alignedItem?.rectified?.edgeMatchScore ?: 1f
                            MetricDepthObservation(
                                rawDepthMm = warpedDepth.millimeters,
                                width = warpedDepth.width,
                                height = warpedDepth.height,
                                weight = edgeFactor / (1f + distFromHero * 1.3f + f.gyroMag),
                                isNearHero = distFromHero <= 0.30f
                            )
                        }
                        val heroMetricObs = hero.depthBuffer?.let { db ->
                            MetricDepthObservation(
                                rawDepthMm = db.millimeters,
                                width = db.width,
                                height = db.height,
                                weight = 1.6f / (1f + hero.gyroMag),
                                isNearHero = true
                            )
                        }

                        val depthResult: RefinedDepthResult = if (metricObservations.isNotEmpty()) {
                            DynamicDepthCodec.fuseMultiFrameMetricDepth(
                                colorBitmap = enhancedHero,
                                heroDepth = heroMetricObs,
                                allDepths = metricObservations,
                                stereoFallbackNeighbors = stereoNeighbors
                            )
                        } else {
                            DynamicDepthCodec.estimateAllFramesStereoDepth(
                                centerBitmap = enhancedHero,
                                neighbors = stereoNeighbors
                            )
                        }

                        val sweepData = framesSnapshot.map { f ->
                            CapturedFrameData(
                                bitmap = f.colorBitmap,
                                tx = f.tx, ty = f.ty, tz = f.tz,
                                qx = f.qx, qy = f.qy, qz = f.qz, qw = f.qw
                            )
                        }

                        StagedCapture(
                            heroBitmap = enhancedHero,
                            depthResult = depthResult,
                            sweepFrames = sweepData
                        )
                    } finally {
                        alignedNeighbors.forEach {
                            if (!it.rectified.bitmap.isRecycled) {
                                it.rectified.bitmap.recycle()
                            }
                        }
                    }
                }

                discardStagedCapture()
                _stagedCapture.value = staged
                _uiState.value = _uiState.value.copy(
                    phase = CapturePhase.READY,
                    tileDetails = List(TILE_COUNT) { 0f },
                    tileQualities = List(TILE_COUNT) { 0f },
                    weakestAreaIndex = null,
                    lastFrameComparison = FrameComparisonStatus.NONE,
                    clearedTilesCount = 0,
                    detailPercent = 0,
                    capturedFrameCount = 0,
                    qualityScore = 0f,
                    qualityTier = CaptureQualityTier.POOR,
                    canAcceptCapture = false,
                    brushX = 0f,
                    brushY = 0f,
                    tiltMeterX = 0f,
                    tiltMeterY = 0f,
                    isTiltOffLevel = false,
                    requestFrameGrab = false,
                    errorMessage = null
                )
                onReadyForReview()
            } catch (t: Throwable) {
                framesSnapshot.forEach { it.colorBitmap.recycle() }
                _uiState.value = _uiState.value.copy(
                    phase = CapturePhase.READY,
                    tileDetails = List(TILE_COUNT) { 0f },
                    tileQualities = List(TILE_COUNT) { 0f },
                    weakestAreaIndex = null,
                    lastFrameComparison = FrameComparisonStatus.NONE,
                    clearedTilesCount = 0,
                    detailPercent = 0,
                    capturedFrameCount = 0,
                    qualityScore = 0f,
                    qualityTier = CaptureQualityTier.POOR,
                    canAcceptCapture = false,
                    brushX = 0f,
                    brushY = 0f,
                    tiltMeterX = 0f,
                    tiltMeterY = 0f,
                    isTiltOffLevel = false,
                    requestFrameGrab = false,
                    errorMessage = t.message ?: "Could not create 3D photo"
                )
            }
        }
    }

    fun saveStagedCapture(onSaved: (String) -> Unit) {
        val current = _stagedCapture.value ?: return
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val entity = repository.saveCapturedSpatialPhoto(
                    heroBitmap = current.heroBitmap,
                    depthResult = current.depthResult,
                    sweepFrames = current.sweepFrames
                )
                _uiState.value = _uiState.value.copy(isSaving = false)
                onSaved(entity.id)
                discardStagedCapture()
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = t.message ?: "Could not save 3D photo"
                )
            }
        }
    }

    fun resetForRetake() {
        clearFramePool()
        resetWaveTracking()
        _stagedCapture.value = null
        _uiState.value = _uiState.value.copy(
            phase = CapturePhase.READY,
            tileDetails = List(TILE_COUNT) { 0f },
            tileQualities = List(TILE_COUNT) { 0f },
            weakestAreaIndex = null,
            lastFrameComparison = FrameComparisonStatus.NONE,
            clearedTilesCount = 0,
            detailPercent = 0,
            capturedFrameCount = 0,
            qualityScore = 0f,
            qualityTier = CaptureQualityTier.POOR,
            canAcceptCapture = false,
            brushX = 0f,
            brushY = 0f,
            tiltMeterX = 0f,
            tiltMeterY = 0f,
            isTiltOffLevel = false,
            requestFrameGrab = false,
            isMovingTooFast = false,
            errorMessage = null
        )
    }

    fun discardStagedCapture() {
        _stagedCapture.value = null
    }

    private fun clearFramePool() {
        framePool.forEach { it.colorBitmap.recycle() }
        framePool.clear()
    }

    override fun onCleared() {
        clearFramePool()
        discardStagedCapture()
        stopSensors()
        super.onCleared()
    }

    private fun measureDepthCoverage(buffer: Depth16Buffer?): Float {
        if (buffer == null || buffer.width <= 0 || buffer.height <= 0 || buffer.millimeters.isEmpty()) return 0f
        val stepX = max(1, buffer.width / 16)
        val stepY = max(1, buffer.height / 16)
        var valid = 0
        var total = 0
        var y = stepY / 2
        while (y < buffer.height) {
            val rowOff = y * buffer.width
            var x = stepX / 2
            while (x < buffer.width) {
                val d = buffer.millimeters[rowOff + x].toInt() and 0xFFFF
                if (d in 150..12000) valid++
                total++
                x += stepX
            }
            y += stepY
        }
        return if (total > 0) valid.toFloat() / total else 0f
    }

    companion object {
        const val GRID_COLS = 3
        const val GRID_ROWS = 3
        const val TILE_COUNT = GRID_COLS * GRID_ROWS
        const val TILE_DONE_THRESHOLD = 0.85f

        private const val MIN_FRAMES_TO_ACCEPT = 2
        private const val MAX_FRAME_POOL = 16
        private const val SAME_VIEWPOINT_THRESHOLD = 0.24f
        private const val TILT_LEVEL_TOLERANCE_DEG = 4.2f
        private const val MAX_CAPTURE_GYRO_RAD_PER_SEC = 1.35f

        // Natural ~2.2-inch left/right and up/down wave radius
        private const val WAVE_HALF_SPAN_METERS = 0.055f
        private const val WAVE_HALF_SPAN_DEG = 5.5f

        internal fun computeWeakestAreaIndex(tileQualities: List<Float>): Int? {
            if (tileQualities.isEmpty()) return null
            var minIdx = 0
            var minVal = tileQualities[0]
            var maxVal = tileQualities[0]
            for (i in 1 until tileQualities.size) {
                val q = tileQualities[i]
                if (q < minVal) {
                    minVal = q
                    minIdx = i
                }
                if (q > maxVal) maxVal = q
            }
            if (maxVal < 0.25f) return null
            return if (minVal < 0.65f || (maxVal - minVal) >= 0.18f && minVal < 0.78f) minIdx else null
        }

        internal fun computeBrushGridPos(
            nx: Float,
            ny: Float,
            cols: Int = GRID_COLS,
            rows: Int = GRID_ROWS
        ): Pair<Float, Float> {
            val brushCol = (((nx / 0.80f) + 1f) * 0.5f * (cols - 1)).coerceIn(0f, (cols - 1).toFloat())
            val brushRow = (((ny / 0.80f) + 1f) * 0.5f * (rows - 1)).coerceIn(0f, (rows - 1).toFloat())
            return brushCol to brushRow
        }

        internal fun computeSingleFrameQualityMetric(
            sharpness: Float,
            gyroMag: Float,
            tiltDeg: Float,
            depthCoverage: Float
        ): Float {
            val sharpnessNorm = (sharpness / 36f).coerceIn(0f, 1.5f)
            val steadyNorm = 1f / (1f + gyroMag.coerceAtLeast(0f) * 2.2f)
            val levelNorm = 1f / (1f + tiltDeg.coerceAtLeast(0f) * 0.22f)
            val depthNorm = depthCoverage.coerceIn(0f, 1f)
            return (0.42f * sharpnessNorm + 0.34f * steadyNorm + 0.14f * levelNorm + 0.10f * depthNorm)
                .coerceIn(0f, 1.5f)
        }

        internal fun shouldReplaceWithBetterFrame(
            candidateQuality: Float,
            previousQuality: Float
        ): Boolean = candidateQuality > previousQuality * 1.02f

        internal fun computeCaptureQualityScore(
            frameCount: Int,
            spanX: Float,
            spanY: Float,
            avgTileDetail: Float,
            avgGyroMag: Float,
            hasMetricDepthFrames: Boolean,
            avgFrameQuality: Float = 0.7f
        ): Pair<Float, CaptureQualityTier> {
            if (frameCount <= 0) return 0f to CaptureQualityTier.POOR
            val frameScore = (frameCount / 7f).coerceIn(0f, 1f) * 0.32f
            val hSpanScore = (spanX / 0.85f).coerceIn(0f, 1f) * 0.20f
            val vSpanScore = (spanY / 0.70f).coerceIn(0f, 1f) * 0.16f
            val coverageScore = avgTileDetail.coerceIn(0f, 1f) * 0.10f
            val steadyScore = (1f - (avgGyroMag / 1.4f)).coerceIn(0f, 1f) * 0.14f
            val depthBonus = when {
                hasMetricDepthFrames -> 0.08f
                frameCount >= 3 -> 0.05f
                else -> 0f
            }
            val frameQualityShift = (avgFrameQuality.coerceIn(0f, 1.2f) - 0.7f) * 0.12f
            val score = (frameScore + hSpanScore + vSpanScore + coverageScore + steadyScore + depthBonus + frameQualityShift)
                .coerceIn(0f, 1f)
            val tier = when {
                score >= 0.82f -> CaptureQualityTier.EXCELLENT
                score >= 0.62f -> CaptureQualityTier.GOOD
                score >= 0.35f -> CaptureQualityTier.FAIR
                else -> CaptureQualityTier.POOR
            }
            return score to tier
        }
    }
}
