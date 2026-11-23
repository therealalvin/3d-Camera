package com.example

import androidx.compose.ui.geometry.Offset
import com.example.capture.CaptureQualityTier
import com.example.capture.CaptureViewModel
import com.example.capture.SmartCaptureHelpers
import com.example.viewer.SpatialViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ExampleUnitTest {

    @Test
    fun computeTileDepthDensitiesIdentifiesSparseAndDenseTiles() {
        val w = 32
        val h = 32
        val rawMm = ShortArray(w * h)
        // Populate top-left 8x8 tile (col 0, row 0) completely with 1200mm; leave bottom-right empty
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                rawMm[y * w + x] = 1200
            }
        }
        val densities = SmartCaptureHelpers.computeTileDepthDensities(rawMm, w, h, cols = 4, rows = 4)
        assertEquals(16, densities.size)
        assertTrue(densities[0] > 0.9f)
        assertEquals(0f, densities[15], 1e-4f)
    }

    @Test
    fun worldDeltaProjectsOntoRotatedCameraRightAndUp() {
        // Camera yawed +90° CCW around world +Y (facing -X): local Right is world -Z, local Up is world +Y
        val half = sqrt(0.5f)
        val (dxRight, dyUp) = SmartCaptureHelpers.projectWorldDeltaToCameraRightUp(
            dwx = 0f,
            dwy = 0.03f,
            dwz = -0.04f,
            qx = 0f,
            qy = half,
            qz = 0f,
            qw = half
        )
        assertEquals(0.04f, dxRight, 1e-4f)
        assertEquals(0.03f, dyUp, 1e-4f)
    }

    @Test
    fun computeBrushGridPosMapsTopBottomLeftRightCorrectly() {
        // Moving camera up => aimUpDeg > 0 => ny < 0 => top row (0)
        val (_, topRow) = CaptureViewModel.computeBrushGridPos(nx = 0f, ny = -0.8f)
        assertEquals(0f, topRow, 1e-4f)

        // Moving camera down => ny > 0 => bottom row (2 in 3x3 grid)
        val (_, bottomRow) = CaptureViewModel.computeBrushGridPos(nx = 0f, ny = 0.8f)
        assertEquals(2f, bottomRow, 1e-4f)

        // Moving camera left => nx < 0 => left column (0)
        val (leftCol, _) = CaptureViewModel.computeBrushGridPos(nx = -0.8f, ny = 0f)
        assertEquals(0f, leftCol, 1e-4f)

        // Moving camera right => nx > 0 => right column (2 in 3x3 grid)
        val (rightCol, _) = CaptureViewModel.computeBrushGridPos(nx = 0.8f, ny = 0f)
        assertEquals(2f, rightCol, 1e-4f)
    }

    @Test
    fun quaternionAngleDegreesDetectsSmallTiltDrift() {
        // 10° roll around Z axis (sin(5°) ≈ 0.08716, cos(5°) ≈ 0.99619)
        val qz = kotlin.math.sin(Math.toRadians(5.0)).toFloat()
        val qw = kotlin.math.cos(Math.toRadians(5.0)).toFloat()
        val angleDeg = SmartCaptureHelpers.quaternionAngleDegrees(
            0f, 0f, 0f, 1f,
            0f, 0f, qz, qw
        )
        assertEquals(10f, angleDeg, 0.05f)
    }

    @Test
    fun computeRelativePoseToHeroExtractsRollFromQuaternions() {
        // Negative Z quaternion (-10° around +Z) rolls camera CW so up tilts toward +right
        val qz = kotlin.math.sin(Math.toRadians(-5.0)).toFloat()
        val qw = kotlin.math.cos(Math.toRadians(-5.0)).toFloat()
        val rel = SmartCaptureHelpers.computeRelativePoseToHero(
            heroQx = 0f, heroQy = 0f, heroQz = 0f, heroQw = 1f,
            frameQx = 0f, frameQy = 0f, frameQz = qz, frameQw = qw
        )
        assertEquals(10f, rel.relRollDeg, 0.1f)
        assertEquals(0f, rel.relYawDeg, 0.1f)
        assertEquals(0f, rel.relPitchDeg, 0.1f)
    }

    @Test
    fun computeCaptureQualityScoreProgressesFromPoorToExcellent() {
        val (poorScore, poorTier) = CaptureViewModel.computeCaptureQualityScore(
            frameCount = 1,
            spanX = 0f,
            spanY = 0f,
            avgTileDetail = 0.05f,
            avgGyroMag = 0.2f,
            hasMetricDepthFrames = false
        )
        assertEquals(CaptureQualityTier.POOR, poorTier)
        assertTrue(poorScore < 0.35f)

        val (fairScore, fairTier) = CaptureViewModel.computeCaptureQualityScore(
            frameCount = 3,
            spanX = 0.45f,
            spanY = 0.25f,
            avgTileDetail = 0.35f,
            avgGyroMag = 0.3f,
            hasMetricDepthFrames = false
        )
        assertEquals(CaptureQualityTier.FAIR, fairTier)
        assertTrue(fairScore in 0.35f..0.62f)

        val (goodScore, goodTier) = CaptureViewModel.computeCaptureQualityScore(
            frameCount = 6,
            spanX = 0.70f,
            spanY = 0.50f,
            avgTileDetail = 0.65f,
            avgGyroMag = 0.25f,
            hasMetricDepthFrames = false
        )
        assertEquals(CaptureQualityTier.GOOD, goodTier)
        assertTrue(goodScore in 0.62f..0.82f)

        val (excScore, excTier) = CaptureViewModel.computeCaptureQualityScore(
            frameCount = 8,
            spanX = 0.90f,
            spanY = 0.75f,
            avgTileDetail = 0.85f,
            avgGyroMag = 0.15f,
            hasMetricDepthFrames = true
        )
        assertEquals(CaptureQualityTier.EXCELLENT, excTier)
        assertTrue(excScore >= 0.82f)
    }

    @Test
    fun spatialViewportStateSupportsFingerDragLookPinchZoomAndReset() {
        val state = SpatialViewportState()

        // Drag right & up without zooming: shifts 3D tilt smoothly, zoom stays 1f
        state.applyTransform(
            centroid = Offset(500f, 500f),
            pan = Offset(180f, -90f),
            zoomChange = 1f,
            viewWidth = 1000f,
            viewHeight = 2000f
        )
        assertEquals(0.5f, state.lookX, 0.01f)
        assertEquals(-0.25f, state.lookY, 0.01f)
        assertEquals(1f, state.zoom, 1e-4f)
        assertEquals(0f, state.panX, 1e-4f)
        assertEquals(0f, state.panY, 1e-4f)

        // Pinch in 2.5x around top-left quadrant
        state.applyTransform(
            centroid = Offset(250f, 500f),
            pan = Offset.Zero,
            zoomChange = 2.5f,
            viewWidth = 1000f,
            viewHeight = 2000f
        )
        assertEquals(2.5f, state.zoom, 1e-4f)
        assertTrue(state.panX != 0f || state.panY != 0f)

        // Pinch out past minimum zoom: clamps to 1.0f and recenters pan
        state.applyTransform(
            centroid = Offset(500f, 1000f),
            pan = Offset.Zero,
            zoomChange = 0.2f,
            viewWidth = 1000f,
            viewHeight = 2000f
        )
        assertEquals(1f, state.zoom, 1e-4f)
        assertEquals(0f, state.panX, 1e-4f)
        assertEquals(0f, state.panY, 1e-4f)

        // Twist with two fingers rotates rollDeg around Z axis and wraps cleanly
        state.applyTransform(
            centroid = Offset(500f, 1000f),
            pan = Offset.Zero,
            zoomChange = 1f,
            viewWidth = 1000f,
            viewHeight = 2000f,
            rotationChange = 45f
        )
        assertEquals(45f, state.rollDeg, 0.01f)
        state.applyTransform(
            centroid = Offset(500f, 1000f),
            pan = Offset.Zero,
            zoomChange = 1f,
            viewWidth = 1000f,
            viewHeight = 2000f,
            rotationChange = 160f
        )
        assertEquals(-155f, state.rollDeg, 0.01f)

        // Reset restores centered neutral state including 3D roll
        state.reset()
        assertEquals(0f, state.lookX, 1e-4f)
        assertEquals(0f, state.lookY, 1e-4f)
        assertEquals(0f, state.rollDeg, 1e-4f)
        assertEquals(1f, state.zoom, 1e-4f)
    }

    @Test
    fun computeWeakestAreaIndexFlagsLaggingTileAndClearsWhenBalanced() {
        val balanced = List(9) { 0.85f }
        assertEquals(null, CaptureViewModel.computeWeakestAreaIndex(balanced))

        val weakTopLeft = listOf(
            0.22f, 0.78f, 0.80f,
            0.76f, 0.88f, 0.79f,
            0.75f, 0.82f, 0.77f
        )
        assertEquals(0, CaptureViewModel.computeWeakestAreaIndex(weakTopLeft))
    }

    @Test
    fun computeSingleFrameQualityMetricPrefersSharperSteadierFrames() {
        val blurryShaky = CaptureViewModel.computeSingleFrameQualityMetric(
            sharpness = 9f,
            gyroMag = 0.9f,
            tiltDeg = 4.5f,
            depthCoverage = 0.2f
        )
        val crispSteady = CaptureViewModel.computeSingleFrameQualityMetric(
            sharpness = 28f,
            gyroMag = 0.1f,
            tiltDeg = 0.8f,
            depthCoverage = 0.8f
        )
        assertTrue(crispSteady > blurryShaky)
        assertTrue(CaptureViewModel.shouldReplaceWithBetterFrame(crispSteady, blurryShaky))
        assertFalse(CaptureViewModel.shouldReplaceWithBetterFrame(blurryShaky, crispSteady))
    }
}
