package com.example.capture

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Depth16Buffer(
    val millimeters: ShortArray,
    val width: Int,
    val height: Int
)

data class RelativeFramePose(
    val relYawDeg: Float,    // Frame aimed Right (+) relative to Hero
    val relPitchDeg: Float,  // Frame aimed Up (+) relative to Hero
    val relRollDeg: Float,   // Frame rolled CW (+) relative to Hero
    val totalAngleDeg: Float
)

object SmartCaptureHelpers {

    fun getDisplayRotation(context: Context): Int {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    fun getBackCameraDisplayRotationDegrees(context: Context): Int {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: return 90
        val sensorOrientation = cm.getCameraCharacteristics(backId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val displayDegrees = when (getDisplayRotation(context)) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - displayDegrees + 360) % 360
    }

    fun rotateDepth16(
        rawMm: ShortArray,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ): Depth16Buffer {
        val norm = ((rotationDegrees % 360) + 360) % 360
        if (norm == 0) return Depth16Buffer(rawMm, width, height)
        val out = ShortArray(rawMm.size)
        return when (norm) {
            90 -> {
                val newW = height
                val newH = width
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val nx = height - 1 - y
                        val ny = x
                        out[ny * newW + nx] = rawMm[y * width + x]
                    }
                }
                Depth16Buffer(out, newW, newH)
            }
            180 -> {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val nx = width - 1 - x
                        val ny = height - 1 - y
                        out[ny * width + nx] = rawMm[y * width + x]
                    }
                }
                Depth16Buffer(out, width, height)
            }
            270 -> {
                val newW = height
                val newH = width
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val nx = y
                        val ny = width - 1 - x
                        out[ny * newW + nx] = rawMm[y * width + x]
                    }
                }
                Depth16Buffer(out, newW, newH)
            }
            else -> Depth16Buffer(rawMm, width, height)
        }
    }

    fun medianCenterDepthMeters(rawMm: ShortArray, width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0 || rawMm.isEmpty()) return null
        val samples = ArrayList<Float>(100)
        val xStart = (width * 0.35f).toInt()
        val xEnd = (width * 0.65f).toInt()
        val yStart = (height * 0.35f).toInt()
        val yEnd = (height * 0.65f).toInt()
        for (y in yStart until yEnd step 4) {
            for (x in xStart until xEnd step 4) {
                val mm = rawMm[y * width + x].toInt() and 0xFFFF
                if (mm in 150..12000) {
                    samples.add(mm / 1000f)
                }
            }
        }
        if (samples.size < 8) return null
        samples.sort()
        return samples[samples.size / 2]
    }

    fun translationDistance(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float
    ): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun quaternionAngleDegrees(
        aqx: Float, aqy: Float, aqz: Float, aqw: Float,
        bqx: Float, bqy: Float, bqz: Float, bqw: Float
    ): Float {
        val dot = (aqx * bqx + aqy * bqy + aqz * bqz + aqw * bqw)
            .let { abs(it) }
            .coerceIn(0f, 1f)
        val rad = 2f * acos(dot)
        return rad * 180f / PI.toFloat()
    }

    /**
     * Computes gimbal-lock-free relative yaw (pan right), pitch (tilt up), and in-plane roll (CW)
     * of a neighbor frame with respect to the Hero frame's optical basis.
     */
    fun computeRelativePoseToHero(
        heroQx: Float, heroQy: Float, heroQz: Float, heroQw: Float,
        frameQx: Float, frameQy: Float, frameQz: Float, frameQw: Float,
        heroAimYawDeg: Float = 0f,
        heroAimUpDeg: Float = 0f,
        heroRollRightDeg: Float = 0f,
        frameAimYawDeg: Float = 0f,
        frameAimUpDeg: Float = 0f,
        frameRollRightDeg: Float = 0f
    ): RelativeFramePose {
        val hasHeroQ = (heroQx * heroQx + heroQy * heroQy + heroQz * heroQz + heroQw * heroQw) > 0.25f
        val hasFrameQ = (frameQx * frameQx + frameQy * frameQy + frameQz * frameQz + frameQw * frameQw) > 0.25f
        val heroIdentityOnly = heroQx == 0f && heroQy == 0f && heroQz == 0f && heroQw == 1f &&
            frameQx == 0f && frameQy == 0f && frameQz == 0f && frameQw == 1f

        if (hasHeroQ && hasFrameQ && !heroIdentityOnly) {
            // Hero orthonormal camera basis (Right, Up, Forward = -ScreenNormal)
            val hRx = 1f - 2f * (heroQy * heroQy + heroQz * heroQz)
            val hRy = 2f * (heroQx * heroQy + heroQw * heroQz)
            val hRz = 2f * (heroQx * heroQz - heroQw * heroQy)

            val hUx = 2f * (heroQx * heroQy - heroQw * heroQz)
            val hUy = 1f - 2f * (heroQx * heroQx + heroQz * heroQz)
            val hUz = 2f * (heroQy * heroQz + heroQw * heroQx)

            val hFx = -2f * (heroQx * heroQz + heroQw * heroQy)
            val hFy = -2f * (heroQy * heroQz - heroQw * heroQx)
            val hFz = -(1f - 2f * (heroQx * heroQx + heroQy * heroQy))

            // Frame orthonormal camera basis
            val fUx = 2f * (frameQx * frameQy - frameQw * frameQz)
            val fUy = 1f - 2f * (frameQx * frameQx + frameQz * frameQz)
            val fUz = 2f * (frameQy * frameQz + frameQw * frameQx)

            val fFx = -2f * (frameQx * frameQz + frameQw * frameQy)
            val fFy = -2f * (frameQy * frameQz - frameQw * frameQx)
            val fFz = -(1f - 2f * (frameQx * frameQx + frameQy * frameQy))

            val fwdDotRight = (fFx * hRx + fFy * hRy + fFz * hRz)
            val fwdDotUp = (fFx * hUx + fFy * hUy + fFz * hUz)
            val fwdDotFwd = (fFx * hFx + fFy * hFy + fFz * hFz).coerceAtLeast(1e-4f)

            val upDotRight = fUx * hRx + fUy * hRy + fUz * hRz
            val upDotUp = fUx * hUx + fUy * hUy + fUz * hUz

            val radToDeg = 180f / PI.toFloat()
            val relYaw = atan2(fwdDotRight, fwdDotFwd) * radToDeg
            val relPitch = atan2(fwdDotUp, fwdDotFwd) * radToDeg
            val relRoll = atan2(upDotRight, upDotUp) * radToDeg
            val total = quaternionAngleDegrees(heroQx, heroQy, heroQz, heroQw, frameQx, frameQy, frameQz, frameQw)
            return RelativeFramePose(relYaw, relPitch, relRoll, total)
        }

        val dYaw = MotionSensorMonitor.wrapAngleDelta(frameAimYawDeg, heroAimYawDeg)
        val dPitch = MotionSensorMonitor.wrapAngleDelta(frameAimUpDeg, heroAimUpDeg)
        val dRoll = MotionSensorMonitor.wrapAngleDelta(frameRollRightDeg, heroRollRightDeg)
        val total = sqrt(dYaw * dYaw + dPitch * dPitch + dRoll * dRoll)
        return RelativeFramePose(dYaw, dPitch, dRoll, total)
    }

    /**
     * Projects a world-space displacement (dwx, dwy, dwz) onto the local Right (+X)
     * and Up (+Y) axes defined by orientation quaternion (qx, qy, qz, qw).
     */
    fun projectWorldDeltaToCameraRightUp(
        dwx: Float,
        dwy: Float,
        dwz: Float,
        qx: Float,
        qy: Float,
        qz: Float,
        qw: Float
    ): Pair<Float, Float> {
        val rightX = 1f - 2f * (qy * qy + qz * qz)
        val rightY = 2f * (qx * qy + qw * qz)
        val rightZ = 2f * (qx * qz - qw * qy)

        val upX = 2f * (qx * qy - qw * qz)
        val upY = 1f - 2f * (qx * qx + qz * qz)
        val upZ = 2f * (qy * qz + qw * qx)

        val dxRight = dwx * rightX + dwy * rightY + dwz * rightZ
        val dyUp = dwx * upX + dwy * upY + dwz * upZ
        return dxRight to dyUp
    }

    /**
     * Computes valid ARCore Depth16 coverage [0f..1f] for each cell in a cols x rows grid.
     */
    fun computeTileDepthDensities(
        rawMm: ShortArray,
        width: Int,
        height: Int,
        cols: Int = 4,
        rows: Int = 4
    ): FloatArray {
        val out = FloatArray(cols * rows)
        if (width <= 0 || height <= 0 || rawMm.size < width * height) return out
        val stepX = (width / (cols * 6)).coerceAtLeast(1)
        val stepY = (height / (rows * 6)).coerceAtLeast(1)

        for (r in 0 until rows) {
            val y0 = r * height / rows
            val y1 = ((r + 1) * height / rows).coerceAtMost(height)
            for (c in 0 until cols) {
                val x0 = c * width / cols
                val x1 = ((c + 1) * width / cols).coerceAtMost(width)
                var valid = 0
                var total = 0
                var y = y0
                while (y < y1) {
                    val rowOffset = y * width
                    var x = x0
                    while (x < x1) {
                        val mm = rawMm[rowOffset + x].toInt() and 0xFFFF
                        if (mm in 150..12000) valid++
                        total++
                        x += stepX
                    }
                    y += stepY
                }
                out[r * cols + c] = if (total > 0) (valid.toFloat() / total) else 0f
            }
        }
        return out
    }

    /**
     * Estimates optical sharpness via center-crop gradient energy so the sharpest frame becomes the RGB hero.
     */
    fun measureBitmapSharpness(bitmap: Bitmap): Float {
        val sampleW = 96
        val sampleH = 96
        val thumb = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
        val px = IntArray(sampleW * sampleH)
        thumb.getPixels(px, 0, sampleW, 0, 0, sampleW, sampleH)
        if (thumb !== bitmap) thumb.recycle()

        var energy = 0.0
        var count = 0
        for (y in 16 until sampleH - 16) {
            val row = y * sampleW
            val nextRow = (y + 1) * sampleW
            for (x in 16 until sampleW - 16) {
                val c = px[row + x]
                val cr = px[row + x + 1]
                val cb = px[nextRow + x]
                val lumaC = ((c shr 16) and 0xFF) * 0.3f + ((c shr 8) and 0xFF) * 0.59f + (c and 0xFF) * 0.11f
                val lumaR = ((cr shr 16) and 0xFF) * 0.3f + ((cr shr 8) and 0xFF) * 0.59f + (cr and 0xFF) * 0.11f
                val lumaB = ((cb shr 16) and 0xFF) * 0.3f + ((cb shr 8) and 0xFF) * 0.59f + (cb and 0xFF) * 0.11f
                val dx = lumaC - lumaR
                val dy = lumaC - lumaB
                energy += (dx * dx + dy * dy)
                count++
            }
        }
        return if (count > 0) (energy / count).toFloat() else 0f
    }

    /**
     * Computes normalized [0f..1f] local edge sharpness across a cols x rows spatial grid
     * using 2x2-box smoothed luminance gradients (suppresses single-pixel sensor noise).
     */
    fun measureGridPatchSharpness(
        bitmap: Bitmap,
        cols: Int = 3,
        rows: Int = 3
    ): FloatArray {
        val out = FloatArray(cols * rows)
        if (cols <= 0 || rows <= 0 || bitmap.isRecycled || bitmap.width < 4 || bitmap.height < 4) {
            return out
        }
        val sampleW = 96
        val sampleH = 96
        val thumb = Bitmap.createScaledBitmap(bitmap, sampleW, sampleH, true)
        val px = IntArray(sampleW * sampleH)
        thumb.getPixels(px, 0, sampleW, 0, 0, sampleW, sampleH)
        if (thumb !== bitmap) thumb.recycle()

        val luma = FloatArray(sampleW * sampleH)
        for (i in px.indices) {
            val c = px[i]
            luma[i] = ((c shr 16) and 0xFF) * 0.299f +
                ((c shr 8) and 0xFF) * 0.587f +
                (c and 0xFF) * 0.114f
        }

        for (r in 0 until rows) {
            val y0 = (r * sampleH / rows).coerceIn(1, sampleH - 3)
            val y1 = (((r + 1) * sampleH / rows)).coerceIn(y0 + 1, sampleH - 2)
            for (c in 0 until cols) {
                val x0 = (c * sampleW / cols).coerceIn(1, sampleW - 3)
                val x1 = (((c + 1) * sampleW / cols)).coerceIn(x0 + 1, sampleW - 2)
                var gradSum = 0.0
                var count = 0
                for (y in y0 until y1) {
                    val row = y * sampleW
                    val nextRow = (y + 1) * sampleW
                    val prevRow = (y - 1) * sampleW
                    for (x in x0 until x1) {
                        val b0 = (luma[row + x] + luma[row + x + 1] + luma[nextRow + x] + luma[nextRow + x + 1]) * 0.25f
                        val bx = (luma[row + x - 1] + luma[row + x] + luma[nextRow + x - 1] + luma[nextRow + x]) * 0.25f
                        val by = (luma[prevRow + x] + luma[prevRow + x + 1] + luma[row + x] + luma[row + x + 1]) * 0.25f
                        val dx = abs(b0 - bx)
                        val dy = abs(b0 - by)
                        gradSum += (dx + dy) * 0.5f
                        count++
                    }
                }
                val avgGrad = if (count > 0) (gradSum / count).toFloat() else 0f
                out[r * cols + c] = (avgGrad / 14f).coerceIn(0f, 1f)
            }
        }
        return out
    }

    fun scaleDownMaxEdge(bitmap: Bitmap, maxEdge: Int = 1600): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
