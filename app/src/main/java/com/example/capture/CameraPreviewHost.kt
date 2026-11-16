package com.example.capture

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class LivePoseSnapshot(
    val isTracking: Boolean,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val centerDepthMeters: Float?,
    val tileDepthDensities: FloatArray? = null
)

data class CapturedArFrame(
    val colorBitmap: Bitmap,
    val depthBuffer: Depth16Buffer?,
    val tx: Float,
    val ty: Float,
    val tz: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float
)

/**
 * Selects ARCore (Pixel Hardware Depth Boost) when supported and enabled,
 * and falls back cleanly to CameraX + IMU Sensor Fusion when disabled or unsupported.
 * Supports dynamic portrait & landscape device rotation.
 */
@Composable
fun CameraPreviewHost(
    modifier: Modifier = Modifier,
    depthBoostEnabled: Boolean,
    captureNextRequested: Boolean,
    onFrameAnalyzedPose: (LivePoseSnapshot) -> Unit,
    onFrameCaptured: (CapturedArFrame) -> Unit,
    onArCoreSupportDetected: (isSupported: Boolean) -> Unit,
    onPipelineModeActive: (isArCoreActive: Boolean) -> Unit
) {
    val context = LocalContext.current
    var arCoreSupported by remember {
        mutableStateOf(com.example.data.CameraPreferences.isArCoreSupported.value)
    }

    LaunchedEffect(Unit) {
        if (arCoreSupported != null) {
            onArCoreSupportDetected(arCoreSupported == true)
        } else if (!isArCoreServicesInstalled(context)) {
            arCoreSupported = false
            onArCoreSupportDetected(false)
        } else {
            ArCoreApk.getInstance().checkAvailabilityAsync(context) { availability ->
                val ready = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
                arCoreSupported = ready
                onArCoreSupportDetected(ready)
            }
        }
    }

    val useArCore = when (arCoreSupported) {
        true -> depthBoostEnabled
        false -> false
        null -> null
    }

    LaunchedEffect(useArCore) {
        if (useArCore != null) {
            onPipelineModeActive(useArCore)
        }
    }

    when (useArCore) {
        true -> ArCoreViewfinder(
            modifier = modifier,
            captureNextRequested = captureNextRequested,
            onFrameAnalyzedPose = onFrameAnalyzedPose,
            onFrameCaptured = onFrameCaptured,
            onArCoreUnavailable = {
                arCoreSupported = false
                onArCoreSupportDetected(false)
                onPipelineModeActive(false)
            }
        )
        false -> CameraXFallbackViewfinder(
            modifier = modifier,
            captureNextRequested = captureNextRequested,
            onFrameCaptured = onFrameCaptured
        )
        null -> Unit
    }
}

private fun isArCoreServicesInstalled(context: Context): Boolean {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                "com.google.ar.core",
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("com.google.ar.core", 0)
        }
        true
    }.getOrDefault(false)
}

@Composable
private fun ArCoreViewfinder(
    modifier: Modifier = Modifier,
    captureNextRequested: Boolean,
    onFrameAnalyzedPose: (LivePoseSnapshot) -> Unit,
    onFrameCaptured: (CapturedArFrame) -> Unit,
    onArCoreUnavailable: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val captureFlag = remember { AtomicBoolean(false) }
    captureFlag.set(captureNextRequested)

    val poseCallback by rememberUpdatedState(onFrameAnalyzedPose)
    val capturedCallback by rememberUpdatedState(onFrameCaptured)
    val fallbackCallback by rememberUpdatedState(onArCoreUnavailable)

    val sessionRef = remember { AtomicReference<Session?>(null) }
    val sessionLock = remember { Any() }
    val backgroundRenderer = remember { ArCoreBackgroundRenderer() }
    val isCapturingBusy = remember { AtomicBoolean(false) }

    val glView = remember {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(object : GLSurfaceView.Renderer {
                private var textureBoundSession: Session? = null
                private var geometryAppliedSession: Session? = null
                private var surfaceW = 0
                private var surfaceH = 0
                private var lastRotation = -1
                private var lastAppliedW = -1
                private var lastAppliedH = -1

                override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                    GLES20.glClearColor(0.04f, 0.05f, 0.07f, 1f)
                    backgroundRenderer.createOnGlThread()
                    textureBoundSession = null
                    geometryAppliedSession = null
                }

                override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                    GLES20.glViewport(0, 0, width, height)
                    surfaceW = width
                    surfaceH = height
                    geometryAppliedSession = null
                }

                override fun onDrawFrame(gl: GL10?) {
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
                    var poseSnapshot: LivePoseSnapshot? = null
                    var capturedFrame: CapturedArFrame? = null

                    synchronized(sessionLock) {
                        val activeSession = sessionRef.get() ?: return
                        if (textureBoundSession !== activeSession && backgroundRenderer.textureId != -1) {
                            activeSession.setCameraTextureName(backgroundRenderer.textureId)
                            textureBoundSession = activeSession
                        }
                        val currentRot = SmartCaptureHelpers.getDisplayRotation(context)
                        if (surfaceW > 0 && surfaceH > 0 && (
                                geometryAppliedSession !== activeSession ||
                                    currentRot != lastRotation ||
                                    surfaceW != lastAppliedW ||
                                    surfaceH != lastAppliedH
                                )
                        ) {
                            activeSession.setDisplayGeometry(currentRot, surfaceW, surfaceH)
                            geometryAppliedSession = activeSession
                            lastRotation = currentRot
                            lastAppliedW = surfaceW
                            lastAppliedH = surfaceH
                        }

                        val frame = runCatching { activeSession.update() }.getOrNull() ?: return
                        backgroundRenderer.draw(frame)

                        val camera = frame.camera
                        val tracking = camera.trackingState == TrackingState.TRACKING
                        val pose = camera.displayOrientedPose
                        val rotDeg = SmartCaptureHelpers.getBackCameraDisplayRotationDegrees(context)

                        var depthBuffer: Depth16Buffer? = null
                        try {
                            frame.acquireDepthImage16Bits().use { depthImg ->
                                val w = depthImg.width
                                val h = depthImg.height
                                val plane = depthImg.planes[0]
                                val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                                val shortBuf = buf.asShortBuffer()
                                val rawMm = ShortArray(w * h)
                                if (plane.rowStride == w * 2) {
                                    shortBuf.get(rawMm)
                                } else {
                                    val rowShorts = plane.rowStride / 2
                                    for (r in 0 until h) {
                                        shortBuf.position(r * rowShorts)
                                        shortBuf.get(rawMm, r * w, w)
                                    }
                                }
                                depthBuffer = SmartCaptureHelpers.rotateDepth16(rawMm, w, h, rotDeg)
                            }
                        } catch (_: NotYetAvailableException) {
                            // Depth warms up over the first few frames of motion
                        } catch (_: Throwable) {
                        }

                        val centerZ = depthBuffer?.let {
                            SmartCaptureHelpers.medianCenterDepthMeters(it.millimeters, it.width, it.height)
                        }
                        val tileDensities = depthBuffer?.let {
                            SmartCaptureHelpers.computeTileDepthDensities(
                                rawMm = it.millimeters,
                                width = it.width,
                                height = it.height,
                                cols = CaptureViewModel.GRID_COLS,
                                rows = CaptureViewModel.GRID_ROWS
                            )
                        }

                        poseSnapshot = LivePoseSnapshot(
                            isTracking = tracking,
                            tx = pose.tx(),
                            ty = pose.ty(),
                            tz = pose.tz(),
                            qx = pose.qx(),
                            qy = pose.qy(),
                            qz = pose.qz(),
                            qw = pose.qw(),
                            centerDepthMeters = centerZ,
                            tileDepthDensities = tileDensities
                        )

                        if (captureFlag.get() && isCapturingBusy.compareAndSet(false, true)) {
                            try {
                                val colorBmp = frame.acquireCameraImage().use { camImg ->
                                    YuvBitmapConverter.yuv420ImageToBitmap(camImg, rotDeg)
                                }
                                if (colorBmp != null) {
                                    val scaled = SmartCaptureHelpers.scaleDownMaxEdge(colorBmp, 1600)
                                    if (scaled !== colorBmp) colorBmp.recycle()
                                    capturedFrame = CapturedArFrame(
                                        colorBitmap = scaled,
                                        depthBuffer = depthBuffer,
                                        tx = pose.tx(),
                                        ty = pose.ty(),
                                        tz = pose.tz(),
                                        qx = pose.qx(),
                                        qy = pose.qy(),
                                        qz = pose.qz(),
                                        qw = pose.qw()
                                    )
                                }
                            } catch (_: Throwable) {
                            } finally {
                                isCapturingBusy.set(false)
                            }
                        }
                    }

                    poseSnapshot?.let { poseCallback(it) }
                    capturedFrame?.let { capturedCallback(it) }
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(lifecycleOwner) {
        fun resumeArSession() {
            var resumeOk = false
            synchronized(sessionLock) {
                val created = sessionRef.get() ?: runCatching {
                    Session(context).apply {
                        val cfg = Config(this).apply {
                            depthMode = if (isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                                Config.DepthMode.AUTOMATIC
                            } else {
                                Config.DepthMode.DISABLED
                            }
                            focusMode = Config.FocusMode.AUTO
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        }
                        configure(cfg)
                    }
                }.getOrNull()

                if (created != null) {
                    sessionRef.set(created)
                    resumeOk = runCatching {
                        created.resume()
                        true
                    }.getOrDefault(false)
                }
            }

            if (resumeOk) {
                glView.onResume()
            } else {
                fallbackCallback()
            }
        }

        fun pauseArSession() {
            runCatching { glView.onPause() }
            synchronized(sessionLock) {
                runCatching { sessionRef.get()?.pause() }
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumeArSession()
                Lifecycle.Event.ON_PAUSE -> pauseArSession()
                Lifecycle.Event.ON_DESTROY -> {
                    runCatching { glView.onPause() }
                    synchronized(sessionLock) {
                        sessionRef.getAndSet(null)?.let {
                            runCatching {
                                it.pause()
                                it.close()
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resumeArSession()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { glView.onPause() }
            synchronized(sessionLock) {
                sessionRef.getAndSet(null)?.let {
                    runCatching {
                        it.pause()
                        it.close()
                    }
                }
            }
        }
    }

    AndroidView(
        factory = {
            glView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier
    )
}

@Composable
private fun CameraXFallbackViewfinder(
    modifier: Modifier = Modifier,
    captureNextRequested: Boolean,
    onFrameCaptured: (CapturedArFrame) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val captureFlag = remember { AtomicBoolean(false) }
    captureFlag.set(captureNextRequested)
    val capturedCallback by rememberUpdatedState(onFrameCaptured)

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        var isDisposed = false
        var boundAnalysis: ImageAnalysis? = null
        var boundUseCases: List<UseCase> = emptyList()

        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    val rot = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                        ?: Surface.ROTATION_0
                    boundAnalysis?.targetRotation = rot
                }
            }
        }
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        val listener = Runnable {
            if (isDisposed || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                return@Runnable
            }
            val provider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@Runnable
            val initialRot = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                ?: Surface.ROTATION_0
            val preview = Preview.Builder()
                .setTargetRotation(initialRot)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
            val analysis = ImageAnalysis.Builder()
                .setTargetRotation(initialRot)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            boundAnalysis = analysis

            analysis.setAnalyzer(executor) { imageProxy ->
                try {
                    if (captureFlag.compareAndSet(true, false)) {
                        val rawBmp = imageProxy.toBitmap()
                        val upright = YuvBitmapConverter.rotateBitmap(
                            rawBmp,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        val scaled = SmartCaptureHelpers.scaleDownMaxEdge(upright, 1600)
                        if (scaled !== upright) upright.recycle()
                        capturedCallback(
                            CapturedArFrame(
                                colorBitmap = scaled,
                                depthBuffer = null,
                                tx = 0f, ty = 0f, tz = 0f,
                                qx = 0f, qy = 0f, qz = 0f, qw = 1f
                            )
                        )
                    }
                } catch (_: Throwable) {
                } finally {
                    imageProxy.close()
                }
            }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                boundUseCases = listOf(preview, analysis)
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            isDisposed = true
            displayManager.unregisterDisplayListener(displayListener)
            if (cameraProviderFuture.isDone && boundUseCases.isNotEmpty()) {
                runCatching { cameraProviderFuture.get().unbind(*boundUseCases.toTypedArray()) }
            }
            boundUseCases = emptyList()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
