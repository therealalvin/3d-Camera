package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.imaging.DynamicDepthCodec
import com.example.imaging.MetricDepthObservation
import com.example.imaging.StereoNeighborObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DynamicDepthCodecTest {

    @Test
    fun `refineMetricDepthMap and GDepth XMP JPEG roundtrip succeeds`() {
        val colorBmp = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        for (y in 0 until 160) {
            for (x in 0 until 120) {
                val fg = x in 35..85 && y in 40..120
                colorBmp.setPixel(x, y, if (fg) Color.rgb(0, 229, 195) else Color.rgb(20, 25, 35))
            }
        }

        val depthW = 60
        val depthH = 80
        val rawMm = ShortArray(depthW * depthH) { idx ->
            val y = idx / depthW
            val x = idx % depthW
            val fg = x in 17..42 && y in 20..60
            (if (fg) 800 else 3200).toShort()
        }

        val refined = DynamicDepthCodec.refineMetricDepthMap(colorBmp, rawMm, depthW, depthH)
        assertTrue(refined.hasMetricDepth)
        assertTrue(refined.nearMeters < refined.farMeters)

        val jpegBytes = DynamicDepthCodec.encodeDynamicDepthJpeg(
            colorBitmap = colorBmp,
            depthBitmap = refined.depthBitmap,
            nearMeters = refined.nearMeters,
            farMeters = refined.farMeters
        )
        assertTrue(jpegBytes.size > 500)

        val decoded = DynamicDepthCodec.decodeDynamicDepthJpeg(jpegBytes)
        assertNotNull(decoded)
        assertTrue(decoded!!.hasDepthMap)
        assertNotNull(decoded.depthBitmap)
        assertEquals(120, decoded.colorBitmap.width)
        assertEquals(160, decoded.colorBitmap.height)
    }

    @Test
    fun `synthesizeSideBySideStereoJpeg produces double-width stereo frame`() {
        val colorBmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 120, 200))
        }
        val depthBmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(180, 180, 180))
        }

        val sbsBytes = DynamicDepthCodec.synthesizeSideBySideStereoJpeg(
            colorBitmap = colorBmp,
            depthBitmap = depthBmp,
            maxEyeWidth = 160
        )
        val decodedSbs = BitmapFactory.decodeByteArray(sbsBytes, 0, sbsBytes.size)
        assertNotNull(decodedSbs)
        assertEquals(320, decodedSbs.width)
        assertEquals(120, decodedSbs.height)
    }

    @Test
    fun `estimateStereoDepthFromSweep produces valid stretched depth bitmap`() {
        val center = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val neighbor = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until 120) {
            for (x in 0 until 160) {
                val bg = Color.rgb(30, 40, 50)
                val fgCenter = x in 60..100 && y in 30..90
                val fgNeighbor = x in 52..92 && y in 30..90
                center.setPixel(x, y, if (fgCenter) Color.rgb(0, 229, 195) else bg)
                neighbor.setPixel(x, y, if (fgNeighbor) Color.rgb(0, 229, 195) else bg)
            }
        }

        val result = DynamicDepthCodec.estimateStereoDepthFromSweep(center, neighbor)
        assertNotNull(result.depthBitmap)
        assertTrue(result.depthBitmap.width >= 64)
        assertTrue(result.depthBitmap.height >= 64)
        assertTrue(result.nearMeters < result.farMeters)
    }

    @Test
    fun `estimateMultiViewStereoDepthFromCircle combines horizontal and vertical baselines`() {
        val center = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val horizNeighbor = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val vertNeighbor = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until 120) {
            for (x in 0 until 160) {
                val bg = Color.rgb(30, 40, 50)
                val fgC = x in 60..100 && y in 30..90
                val fgH = x in 52..92 && y in 30..90
                val fgV = x in 60..100 && y in 24..84
                center.setPixel(x, y, if (fgC) Color.rgb(0, 229, 195) else bg)
                horizNeighbor.setPixel(x, y, if (fgH) Color.rgb(0, 229, 195) else bg)
                vertNeighbor.setPixel(x, y, if (fgV) Color.rgb(0, 229, 195) else bg)
            }
        }

        val result = DynamicDepthCodec.estimateMultiViewStereoDepthFromCircle(
            centerBitmap = center,
            horizNeighbor = horizNeighbor,
            vertNeighbor = vertNeighbor
        )
        assertNotNull(result.depthBitmap)
        assertTrue(result.depthBitmap.width >= 64)
        assertTrue(result.depthBitmap.height >= 64)
        assertTrue(result.nearMeters < result.farMeters)
    }

    @Test
    fun `fuseMultiFrameMetricDepth fills hero depth holes from additional frames`() {
        val colorBmp = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(25, 35, 50))
        }
        val w = 30
        val h = 40
        // Hero depth has a hole in top half (mm = 0), valid 900mm in bottom half
        val heroMm = ShortArray(w * h) { idx ->
            val y = idx / w
            if (y >= h / 2) 900 else 0
        }
        // Second frame has full coverage (2200mm top half, 900mm bottom half)
        val secondMm = ShortArray(w * h) { idx ->
            val y = idx / w
            if (y >= h / 2) 900 else 2200
        }
        val heroObs = MetricDepthObservation(heroMm, w, h, weight = 1f)
        val secondObs = MetricDepthObservation(secondMm, w, h, weight = 0.8f)

        val fused = DynamicDepthCodec.fuseMultiFrameMetricDepth(
            colorBitmap = colorBmp,
            heroDepth = heroObs,
            allDepths = listOf(heroObs, secondObs)
        )
        assertTrue(fused.hasMetricDepth)
        assertTrue(fused.nearMeters < 1.2f)
        assertTrue(fused.farMeters > 1.8f)
    }

    @Test
    fun `estimateAllFramesStereoDepth fuses multiple wave viewpoints`() {
        val center = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val nbRight = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val nbLeft = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val nbUp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        for (y in 0 until 120) {
            for (x in 0 until 160) {
                val bg = Color.rgb(30, 40, 50)
                val fgC = x in 60..100 && y in 30..90
                val fgR = x in 52..92 && y in 30..90
                val fgL = x in 68..108 && y in 30..90
                val fgU = x in 60..100 && y in 24..84
                center.setPixel(x, y, if (fgC) Color.rgb(0, 229, 195) else bg)
                nbRight.setPixel(x, y, if (fgR) Color.rgb(0, 229, 195) else bg)
                nbLeft.setPixel(x, y, if (fgL) Color.rgb(0, 229, 195) else bg)
                nbUp.setPixel(x, y, if (fgU) Color.rgb(0, 229, 195) else bg)
            }
        }
        val result = DynamicDepthCodec.estimateAllFramesStereoDepth(
            centerBitmap = center,
            neighbors = listOf(
                StereoNeighborObservation(nbRight, dxRight = 0.05f, dyUp = 0f, weight = 1f),
                StereoNeighborObservation(nbLeft, dxRight = -0.05f, dyUp = 0f, weight = 1f),
                StereoNeighborObservation(nbUp, dxRight = 0f, dyUp = 0.04f, weight = 1f)
            )
        )
        assertNotNull(result.depthBitmap)
        assertTrue(result.depthBitmap.width >= 64)
        assertTrue(result.nearMeters < result.farMeters)
    }

    @Test
    fun `enhanceHeroFromMultiFrameSweep reduces stationary noise while preserving dimensions`() {
        val w = 96
        val h = 96
        val hero = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val nb1 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val nb2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val baseR = if (x in 24..72 && y in 24..72) 180 else 60
                val baseG = 100
                val baseB = 140
                val jitterHero = if ((x + y) % 2 == 0) 8 else -8
                hero.setPixel(x, y, Color.rgb(baseR + jitterHero, baseG, baseB))
                nb1.setPixel(x, y, Color.rgb(baseR, baseG, baseB))
                nb2.setPixel(x, y, Color.rgb(baseR, baseG, baseB))
            }
        }

        val enhanced = DynamicDepthCodec.enhanceHeroFromMultiFrameSweep(hero, listOf(nb1, nb2))
        assertEquals(w, enhanced.width)
        assertEquals(h, enhanced.height)
        val centerR = Color.red(enhanced.getPixel(48, 48))
        assertTrue("Stationary jitter should be damped toward 180, got $centerR", centerR in 175..185)
    }

    @Test
    fun `enhanceHeroFromMultiFrameSweep skips heavily skewed or shifted blocks without ghosting`() {
        val w = 96
        val h = 96
        val hero = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 120, 80))
        }
        val skewedNb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 220, 210))
        }

        val enhanced = DynamicDepthCodec.enhanceHeroFromMultiFrameSweep(hero, listOf(skewedNb))
        val pixel = enhanced.getPixel(48, 48)
        assertEquals(200, Color.red(pixel))
        assertEquals(120, Color.green(pixel))
        assertEquals(80, Color.blue(pixel))
    }

    @Test
    fun `fuseMultiFrameMetricDepth uses far frames only for holes without overwriting valid hero depth`() {
        val colorBmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 40, 60))
        }
        val w = 20
        val h = 20
        // Hero is valid 900mm everywhere except top-left quadrant (mm = 0)
        val heroMm = ShortArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x < 10 && y < 10) 0 else 900
        }
        // Distant frame has a parallax-shifted 1050mm border over bottom-right, and 2400mm in top-left hole
        val farMm = ShortArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (x < 10 && y < 10) 2400 else 1050
        }
        val heroObs = MetricDepthObservation(heroMm, w, h, weight = 1.6f, isNearHero = true)
        val farObs = MetricDepthObservation(farMm, w, h, weight = 1.0f, isNearHero = false)

        val fused = DynamicDepthCodec.fuseMultiFrameMetricDepth(
            colorBitmap = colorBmp,
            heroDepth = heroObs,
            allDepths = listOf(heroObs, farObs)
        )
        assertTrue(fused.hasMetricDepth)
        // Hole filled cleanly from 2400mm while hero's 900mm foreground stays intact
        assertTrue(fused.nearMeters < 1.0f)
        assertTrue(fused.farMeters > 2.0f)
    }
}
