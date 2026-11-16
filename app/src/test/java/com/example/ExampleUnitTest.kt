package com.example

import com.example.capture.CaptureViewModel
import com.example.capture.SmartCaptureHelpers
import org.junit.Assert.assertEquals
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
    fun quaternionAngleDegreesDetectsSmallTiltDriftForCaptureGate() {
        // 10° roll around Z axis (sin(5°) ≈ 0.08716, cos(5°) ≈ 0.99619)
        val qz = kotlin.math.sin(Math.toRadians(5.0)).toFloat()
        val qw = kotlin.math.cos(Math.toRadians(5.0)).toFloat()
        val angleDeg = SmartCaptureHelpers.quaternionAngleDegrees(
            0f, 0f, 0f, 1f,
            0f, 0f, qz, qw
        )
        assertEquals(10f, angleDeg, 0.05f)
        assertTrue(angleDeg > 3.6f)
    }
}
