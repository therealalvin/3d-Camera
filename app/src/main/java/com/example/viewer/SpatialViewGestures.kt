package com.example.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.min

@Stable
class SpatialViewportState(
    initialLookX: Float = 0f,
    initialLookY: Float = 0f,
    initialZoom: Float = 1f,
    initialPanX: Float = 0f,
    initialPanY: Float = 0f
) {
    var lookX by mutableFloatStateOf(initialLookX)
        private set
    var lookY by mutableFloatStateOf(initialLookY)
        private set
    var zoom by mutableFloatStateOf(initialZoom)
        private set
    var panX by mutableFloatStateOf(initialPanX)
        private set
    var panY by mutableFloatStateOf(initialPanY)
        private set

    fun applyTransform(
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
        viewWidth: Float,
        viewHeight: Float
    ) {
        if (viewWidth <= 1f || viewHeight <= 1f) return

        val oldZoom = zoom
        val newZoom = (oldZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val scaleRatio = newZoom / oldZoom

        // Convert Compose screen centroid to OpenGL NDC (-1..+1, +Y up)
        val cxNdc = (centroid.x / viewWidth) * 2f - 1f
        val cyNdc = 1f - (centroid.y / viewHeight) * 2f

        val panDeltaNdcX = (pan.x / viewWidth) * 2f
        val panDeltaNdcY = -(pan.y / viewHeight) * 2f

        if (newZoom <= 1.01f) {
            panX = 0f
            panY = 0f
        } else {
            val anchoredPanX = cxNdc - (cxNdc - panX) * scaleRatio + panDeltaNdcX
            val anchoredPanY = cyNdc - (cyNdc - panY) * scaleRatio + panDeltaNdcY
            val maxPan = (newZoom * 1.16f - 1f).coerceAtLeast(0f)
            panX = anchoredPanX.coerceIn(-maxPan, maxPan)
            panY = anchoredPanY.coerceIn(-maxPan, maxPan)
        }
        zoom = newZoom

        // Drive natural finger-follow 3D look-around when dragging
        if (abs(zoomChange - 1f) < 0.04f && (pan.x != 0f || pan.y != 0f)) {
            val lookSpanPx = (min(viewWidth, viewHeight) * 0.34f).coerceAtLeast(48f)
            lookX = (lookX + pan.x / lookSpanPx).coerceIn(-1f, 1f)
            lookY = (lookY + pan.y / lookSpanPx).coerceIn(-1f, 1f)
        }
    }

    fun reset() {
        lookX = 0f
        lookY = 0f
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
    }
}

@Composable
fun rememberSpatialViewportState(): SpatialViewportState = remember { SpatialViewportState() }

fun Modifier.spatialLookAndZoomGestures(
    state: SpatialViewportState,
    onSingleTap: (() -> Unit)? = null
): Modifier = this
    .pointerInput(state, onSingleTap) {
        detectTapGestures(
            onTap = if (onSingleTap != null) {
                { onSingleTap() }
            } else {
                null
            },
            onDoubleTap = {
                state.reset()
            }
        )
    }
    .pointerInput(state) {
        detectTransformGestures(panZoomLock = false) { centroid, pan, zoomChange, _ ->
            state.applyTransform(
                centroid = centroid,
                pan = pan,
                zoomChange = zoomChange,
                viewWidth = size.width.toFloat(),
                viewHeight = size.height.toFloat()
            )
        }
    }
