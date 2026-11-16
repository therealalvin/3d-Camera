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

data class StagedCapture(
    val heroBitmap: Bitmap,
    val depthResult: RefinedDepthResult,
    val sweepFrames: List<CapturedFrameData>
)

data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.READY,
    val tileDetails: List<Float> = List(CaptureViewModel.TILE_COUNT) { 0f },
    val clearedTilesCount: Int = 0,
    val detailPercent: Int = 0,
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
    val nx: Float,
    val ny: Float,
    val gyroMag: Float,
    val tiltDeg: Float
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
        val current = _uiState.value
        when (current.phase) {
            CapturePhase.READY -> beginSweep()
            CapturePhase.SWEEPING -> {
                if (framePool.size >= 3) {
                    finalizeSweepForReview(onCaptureReadyForReview)
                } else {
                    cancelSweep()
                }
            }
            CapturePhase.PROCESSING -> Unit
        }
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
        grabInFlight = false
        pendingGrabNx = 0f
        pendingGrabNy = 0f
        lastGrabAtMs = 0L
        completionTriggered = false
    }

    private fun isMotionTiltAcceptable(isArCoreActive: Boolean, motion: MotionSample): Boolean {
        val dev = if (isArCoreActive) motion.tiltDeviationDeg else motion.rollDeviationDeg
        return dev <= TILT_CAPTURE_GATE_DEG
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
        val tiltAcceptable = isMotionTiltAcceptable(current.isArCoreModeActive, motion)

        currentNx = nx
        currentNy = ny
        val stepDist = hypot(nx - lastBrushNx, ny - lastBrushNy)
        val (brushCol, brushRow) = computeBrushGridPos(nx, ny, GRID_COLS, GRID_ROWS)

        var tilesChanged = false
        if (tiltAcceptable) {
            minNx = min(minNx, nx)
            maxNx = max(maxNx, nx)
            minNy = min(minNy, ny)
            maxNy = max(maxNy, ny)

            val spanX = maxNx - minNx
            val spanY = maxNy - minNy
            val gentleEnoughForPaint = motion.gyroMagnitudeRadPerSec < 1.25f

            // Sample along the motion segment between previous and current positions so movement
            // between boxes smoothly credits the swept path without forcing robotic pauses
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
                        val eraserFalloff = exp(-dSq / 0.78f)

                        var nextVal = prev
                        if (gentleEnoughForPaint && segDist >= 0.008f) {
                            nextVal = max(nextVal, (prev + eraserFalloff * segDist * 0.95f).coerceAtMost(1f))
                        }
                        if (s == subSteps && tileDepthDensities != null && idx < tileDepthDensities.size &&
                            dSq <= 1.15f && (spanX + spanY) > 0.20f
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
        }

        if (stepDist >= 0.015f) {
            lastBrushNx = nx
            lastBrushNy = ny
        }

        val brushMoved = abs(nx - current.brushX) >= 0.025f || abs(ny - current.brushY) >= 0.025f
        if (tilesChanged || brushMoved) {
            val cleared = tileProgress.count { it >= TILE_DONE_THRESHOLD }
            val avgDetail = (tileProgress.sum() / TILE_COUNT * 100f).roundToInt().coerceIn(0, 100)
            _uiState.value = current.copy(
                tileDetails = tileProgress.toList(),
                clearedTilesCount = cleared,
                detailPercent = avgDetail,
                brushX = nx,
                brushY = ny
            )
        }

        if (tiltAcceptable && !grabInFlight && motion.gyroMagnitudeRadPerSec < 0.80f) {
            val now = System.currentTimeMillis()
            val minExistingDist = framePool.minOfOrNull { hypot(nx - it.nx, ny - it.ny) } ?: Float.MAX_VALUE
            // Capture steady frames both over boxes and along the paths in between
            val needMoreEarly = framePool.size < AUTO_FINISH_MIN_FRAMES && now - lastGrabAtMs >= 180L && minExistingDist >= 0.14f
            val newRegionPoint = now - lastGrabAtMs >= 160L && minExistingDist >= 0.22f

            if (needMoreEarly || newRegionPoint) {
                triggerFrameGrab(nx = nx, ny = ny)
            }
        }
    }

    private fun isCaptureGoalMet(): Boolean {
        val cleared = tileProgress.count { it >= TILE_DONE_THRESHOLD }
        val spanX = maxNx - minNx
        val spanY = maxNy - minNy
        // Require all 9/9 areas cleared cleanly with solid intermediate frame coverage
        return cleared >= AUTO_FINISH_MIN_TILES &&
            framePool.size >= AUTO_FINISH_MIN_FRAMES &&
            spanX >= 0.85f &&
            spanY >= 0.70f
    }

    private fun beginSweep() {
        clearFramePool()
        resetWaveTracking()
        sensorMonitor.recenterTilt()
        _uiState.value = _uiState.value.copy(
            phase = CapturePhase.SWEEPING,
            tileDetails = List(TILE_COUNT) { 0f },
            clearedTilesCount = 0,
            detailPercent = 0,
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
            clearedTilesCount = 0,
            detailPercent = 0,
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
            val tiltOk = isMotionTiltAcceptable(state.isArCoreModeActive, motion)
            val activeTiltDeg = if (state.isArCoreModeActive) motion.tiltDeviationDeg else motion.rollDeviationDeg
            grabInFlight = false
            _uiState.value = _uiState.value.copy(requestFrameGrab = false)

            // Reject tilted frames that slipped past the gate while frame was in flight (unless pool empty)
            if (!tiltOk && framePool.isNotEmpty()) {
                frame.colorBitmap.recycle()
                return@post
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
                nx = pendingGrabNx,
                ny = pendingGrabNy,
                gyroMag = motion.gyroMagnitudeRadPerSec,
                tiltDeg = activeTiltDeg
            )

            // Credit detail right under this frame AND bridge in-between overlap from closest nearby level frame
            val (bCol, bRow) = computeBrushGridPos(candidate.nx, candidate.ny, GRID_COLS, GRID_ROWS)
            for (r in 0 until GRID_ROWS) {
                for (c in 0 until GRID_COLS) {
                    val idx = r * GRID_COLS + c
                    val dSq = (c - bCol) * (c - bCol) + (r - bRow) * (r - bRow)
                    if (dSq <= 0.95f) {
                        val boost = exp(-dSq / 0.85f) * 0.42f
                        tileProgress[idx] = max(tileProgress[idx], (tileProgress[idx] + boost).coerceAtMost(1f))
                    }
                }
            }
            val nearestPrev = framePool
                .filter { hypot(candidate.nx - it.nx, candidate.ny - it.ny) in 0.12f..0.75f }
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
                        if (dSq <= 0.65f) {
                            val bridgeBoost = exp(-dSq / 0.65f) * 0.22f
                            tileProgress[idx] = max(tileProgress[idx], (tileProgress[idx] + bridgeBoost).coerceAtMost(1f))
                        }
                    }
                }
            }

            if (framePool.size < MAX_FRAME_POOL) {
                framePool.add(candidate)
            } else {
                // Keep the sharpest, most level frames by replacing the closest wobblier/tilted frame
                val closestIdx = framePool.indices.minByOrNull { i ->
                    hypot(framePool[i].nx - candidate.nx, framePool[i].ny - candidate.ny)
                } ?: -1
                val candPenalty = candidate.gyroMag + candidate.tiltDeg * 0.25f
                val prevPenalty = if (closestIdx >= 0) {
                    framePool[closestIdx].gyroMag + framePool[closestIdx].tiltDeg * 0.25f
                } else {
                    0f
                }
                if (closestIdx >= 0 && candPenalty + 0.10f < prevPenalty) {
                    framePool[closestIdx].colorBitmap.recycle()
                    framePool[closestIdx] = candidate
                } else {
                    candidate.colorBitmap.recycle()
                }
            }

            val cleared = tileProgress.count { it >= TILE_DONE_THRESHOLD }
            val avgDetail = (tileProgress.sum() / TILE_COUNT * 100f).roundToInt().coerceIn(0, 100)
            _uiState.value = _uiState.value.copy(
                tileDetails = tileProgress.toList(),
                clearedTilesCount = cleared,
                detailPercent = avgDetail
            )

            if (!completionTriggered && isCaptureGoalMet()) {
                completionTriggered = true
                finalizeSweepForReview(onCaptureReadyForReview)
            }
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
                    // Pick the single sharpest, centered, most level frame as the RGB Hero
                    val hasAnyMetricDepth = framesSnapshot.any { it.depthBuffer != null }
                    val hero = framesSnapshot.maxByOrNull { f ->
                        val sharpness = SmartCaptureHelpers.measureBitmapSharpness(f.colorBitmap)
                        val centerScore = 1f / (1f + hypot(f.nx, f.ny) * 1.4f)
                        val steadyScore = 1f / (1f + f.gyroMag * 2.0f)
                        val levelScore = 1f / (1f + f.tiltDeg * 1.6f)
                        val depthBonus = if (!hasAnyMetricDepth || f.depthBuffer != null) 1.25f else 0.7f
                        (sharpness + 10f) * centerScore * steadyScore * levelScore * depthBonus
                    } ?: framesSnapshot.first()

                    // Exclude any rotated/skewed frames whose orientation drifted >3.8° from Hero
                    val hasQuatPose = abs(hero.qx) + abs(hero.qy) + abs(hero.qz) + abs(hero.qw) > 0.1f
                    val levelFrames = framesSnapshot.filter { f ->
                        if (f === hero) return@filter true
                        val qAngleOk = if (hasQuatPose && abs(f.qx) + abs(f.qy) + abs(f.qz) + abs(f.qw) > 0.1f) {
                            SmartCaptureHelpers.quaternionAngleDegrees(
                                hero.qx, hero.qy, hero.qz, hero.qw,
                                f.qx, f.qy, f.qz, f.qw
                            ) <= TILT_CAPTURE_GATE_DEG
                        } else {
                            abs(f.tiltDeg - hero.tiltDeg) <= TILT_CAPTURE_GATE_DEG
                        }
                        qAngleOk && f.tiltDeg <= TILT_CAPTURE_GATE_DEG + 0.4f
                    }
                    val usablePool = if (levelFrames.size >= 2) levelFrames else framesSnapshot

                    val nearbySharpBitmaps = usablePool
                        .asSequence()
                        .filter { it !== hero }
                        .sortedBy { hypot(it.nx - hero.nx, it.ny - hero.ny) + it.gyroMag * 0.5f + it.tiltDeg * 0.25f }
                        .take(3)
                        .map { it.colorBitmap }
                        .toList()
                    val enhancedHero = DynamicDepthCodec.enhanceHeroFromMultiFrameSweep(
                        heroBitmap = hero.colorBitmap,
                        nearbyBitmaps = nearbySharpBitmaps
                    )

                    val stereoNeighbors = usablePool
                        .filter { it !== hero }
                        .map { f ->
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
                                bitmap = f.colorBitmap,
                                dxRight = effDx,
                                dyUp = effDy,
                                weight = 1f / (1f + f.gyroMag + f.tiltDeg * 0.5f)
                            )
                        }

                    val metricObservations = usablePool.mapNotNull { f ->
                        val db = f.depthBuffer ?: return@mapNotNull null
                        val distFromHero = hypot(f.nx - hero.nx, f.ny - hero.ny)
                        MetricDepthObservation(
                            rawDepthMm = db.millimeters,
                            width = db.width,
                            height = db.height,
                            weight = 1f / (1f + distFromHero * 1.4f + f.gyroMag + f.tiltDeg * 0.5f),
                            isNearHero = distFromHero <= 0.25f
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
                }

                discardStagedCapture()
                _stagedCapture.value = staged
                _uiState.value = _uiState.value.copy(
                    phase = CapturePhase.READY,
                    tileDetails = List(TILE_COUNT) { 0f },
                    clearedTilesCount = 0,
                    detailPercent = 0,
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
                    clearedTilesCount = 0,
                    detailPercent = 0,
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
            clearedTilesCount = 0,
            detailPercent = 0,
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

    companion object {
        const val GRID_COLS = 3
        const val GRID_ROWS = 3
        const val TILE_COUNT = GRID_COLS * GRID_ROWS
        const val TILE_DONE_THRESHOLD = 0.85f

        private const val AUTO_FINISH_MIN_TILES = TILE_COUNT
        private const val AUTO_FINISH_MIN_FRAMES = 6
        private const val MAX_FRAME_POOL = 12
        private const val TILT_LEVEL_TOLERANCE_DEG = 3.2f
        private const val TILT_CAPTURE_GATE_DEG = 3.6f

        // Natural ~2.2-inch left/right and up/down chalkboard wave radius
        private const val WAVE_HALF_SPAN_METERS = 0.055f
        private const val WAVE_HALF_SPAN_DEG = 5.5f

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
    }
}
