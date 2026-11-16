package com.example.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class RefinedDepthResult(
    val depthBitmap: Bitmap,
    val nearMeters: Float,
    val farMeters: Float,
    val hasMetricDepth: Boolean
)

data class DecodedSpatialPhoto(
    val colorBitmap: Bitmap,
    val depthBitmap: Bitmap?,
    val nearMeters: Float,
    val farMeters: Float,
    val hasDepthMap: Boolean
)

data class MetricDepthObservation(
    val rawDepthMm: ShortArray,
    val width: Int,
    val height: Int,
    val weight: Float = 1f,
    val isNearHero: Boolean = true
)

data class StereoNeighborObservation(
    val bitmap: Bitmap,
    val dxRight: Float,
    val dyUp: Float,
    val weight: Float = 1f
)

object DynamicDepthCodec {

    private const val STD_XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val EXT_XMP_HEADER = "http://ns.adobe.com/xmp/extension/\u0000"
    private const val MAX_STD_XMP_BYTES = 58_000
    private const val EXT_CHUNK_PAYLOAD = 60_000

    /**
     * Edge-aware joint bilateral upsampling of raw ARCore 16-bit depth (in millimeters)
     * aligned to the high-resolution RGB color bitmap edges.
     */
    fun refineMetricDepthMap(
        colorBitmap: Bitmap,
        rawDepthMm: ShortArray,
        depthWidth: Int,
        depthHeight: Int
    ): RefinedDepthResult {
        if (depthWidth <= 0 || depthHeight <= 0 || rawDepthMm.size < depthWidth * depthHeight) {
            return buildFallbackRadialDepth(colorBitmap, hasMetric = false)
        }

        // Convert raw mm -> meters and collect valid samples for robust near/far percentile bounds
        val rawMeters = FloatArray(depthWidth * depthHeight)
        val validSamples = ArrayList<Float>(depthWidth * depthHeight / 4)
        for (i in 0 until depthWidth * depthHeight) {
            val mm = rawDepthMm[i].toInt() and 0xFFFF
            if (mm in 150..18000) {
                val m = mm / 1000f
                rawMeters[i] = m
                if (i % 4 == 0) validSamples.add(m)
            } else {
                rawMeters[i] = 0f
            }
        }

        if (validSamples.size < 32) {
            return buildFallbackRadialDepth(colorBitmap, hasMetric = false)
        }

        validSamples.sort()
        val nearM = validSamples[(validSamples.size * 0.03f).toInt().coerceIn(0, validSamples.lastIndex)].coerceAtLeast(0.2f)
        val farM = validSamples[(validSamples.size * 0.96f).toInt().coerceIn(0, validSamples.lastIndex)].coerceAtLeast(nearM + 0.35f)

        // Target output depth resolution up to 384px max edge (4x+ faster than 512px, higher than GL mesh)
        val maxDim = 384f
        val scale = min(1f, maxDim / max(colorBitmap.width, colorBitmap.height))
        val outW = (colorBitmap.width * scale).roundToInt().coerceAtLeast(64)
        val outH = (colorBitmap.height * scale).roundToInt().coerceAtLeast(64)

        val guideBmp = Bitmap.createScaledBitmap(colorBitmap, outW, outH, true)
        val guidePixels = IntArray(outW * outH)
        guideBmp.getPixels(guidePixels, 0, outW, 0, 0, outW, outH)
        if (guideBmp !== colorBitmap) guideBmp.recycle()

        val guideLuma = IntArray(outW * outH)
        for (i in guidePixels.indices) {
            val c = guidePixels[i]
            guideLuma[i] = ((77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8)
                .coerceIn(0, 255)
        }

        // Fill holes in rawMeters with quick nearest-valid pass
        val filledRaw = fillDepthHoles(rawMeters, depthWidth, depthHeight, farM)

        // Precompute bilateral Gaussian LUTs to avoid ~10M exp() calls on mobile CPU
        val sigmaLumaSq2 = 2f * 22f * 22f
        val sigmaSpaceSq2 = 2f * 1.8f * 1.8f
        val lumaLut1 = FloatArray(256) { d -> exp(-(d * d).toFloat() / sigmaLumaSq2) }
        val spaceLut1 = FloatArray(25)
        for (dy in -2..2) {
            for (dx in -2..2) {
                spaceLut1[(dy + 2) * 5 + (dx + 2)] = exp(-((dx * dx + dy * dy).toFloat()) / sigmaSpaceSq2)
            }
        }

        val outDepths = FloatArray(outW * outH)
        for (y in 0 until outH) {
            val cy = (y * depthHeight / outH).coerceIn(0, depthHeight - 1)
            val rowOut = y * outW
            for (x in 0 until outW) {
                val cx = (x * depthWidth / outW).coerceIn(0, depthWidth - 1)
                val centerLuma = guideLuma[rowOut + x]

                var weightSum = 0f
                var depthSum = 0f

                for (dy in -2..2) {
                    val ry = (cy + dy).coerceIn(0, depthHeight - 1)
                    val gSampleY = ((ry * outH) / depthHeight).coerceIn(0, outH - 1)
                    val gRow = gSampleY * outW
                    val rRow = ry * depthWidth
                    val lutRow = (dy + 2) * 5
                    for (dx in -2..2) {
                        val rx = (cx + dx).coerceIn(0, depthWidth - 1)
                        val gSampleX = ((rx * outW) / depthWidth).coerceIn(0, outW - 1)
                        val dLuma = abs(centerLuma - guideLuma[gRow + gSampleX])
                        val w = lumaLut1[dLuma] * spaceLut1[lutRow + dx + 2]
                        weightSum += w
                        depthSum += w * filledRaw[rRow + rx]
                    }
                }

                outDepths[rowOut + x] =
                    (if (weightSum > 1e-5f) (depthSum / weightSum) else filledRaw[cy * depthWidth + cx])
                        .coerceIn(nearM, farM)
            }
        }

        // Pass 2: Edge-aware polish flattens jittery depth noise on stationary surfaces while locking crisp silhouettes
        val polishLumaSq2 = 2f * 12f * 12f
        val polishSpaceSq2 = 2f * 2.2f * 2.2f
        val lumaLut2 = FloatArray(256) { d -> exp(-(d * d).toFloat() / polishLumaSq2) }
        val spaceLut2 = FloatArray(25)
        for (dy in -2..2) {
            for (dx in -2..2) {
                spaceLut2[(dy + 2) * 5 + (dx + 2)] = exp(-((dx * dx + dy * dy) * 4).toFloat() / polishSpaceSq2)
            }
        }

        val polishedDepths = FloatArray(outW * outH)
        for (y in 0 until outH) {
            val rowOff = y * outW
            for (x in 0 until outW) {
                val centerLuma = guideLuma[rowOff + x]
                var wSum = 0f
                var dSum = 0f
                for (dy in -2..2) {
                    val ny = (y + dy * 2).coerceIn(0, outH - 1)
                    val nRow = ny * outW
                    val lutRow = (dy + 2) * 5
                    for (dx in -2..2) {
                        val nx = (x + dx * 2).coerceIn(0, outW - 1)
                        val dLuma = abs(centerLuma - guideLuma[nRow + nx])
                        val w = lumaLut2[dLuma] * spaceLut2[lutRow + dx + 2]
                        wSum += w
                        dSum += w * outDepths[nRow + nx]
                    }
                }
                polishedDepths[rowOff + x] = (if (wSum > 1e-5f) dSum / wSum else outDepths[rowOff + x])
                    .coerceIn(nearM, farM)
            }
        }

        val outPixels = IntArray(outW * outH)
        val invNear = 1f / nearM
        val invFar = 1f / farM
        val invRange = max(1e-4f, invNear - invFar)
        val borderMarginX = (outW * 0.04f).coerceAtLeast(2f)
        val borderMarginY = (outH * 0.04f).coerceAtLeast(2f)
        for (y in 0 until outH) {
            val edgeY = min(y / borderMarginY, (outH - 1 - y) / borderMarginY).coerceIn(0f, 1f)
            val rowOff = y * outW
            for (x in 0 until outW) {
                val edgeX = min(x / borderMarginX, (outW - 1 - x) / borderMarginX).coerceIn(0f, 1f)
                val borderBlend = min(edgeX, edgeY)
                val invNormRaw = (((1f / polishedDepths[rowOff + x]) - invFar) / invRange).coerceIn(0f, 1f)
                // Gently relax extreme 4% outer margin toward neutral depth (0.35) to eliminate border edge warping
                val invNorm = invNormRaw * borderBlend + 0.35f * (1f - borderBlend)
                val gray = (invNorm * 255f).roundToInt().coerceIn(0, 255)
                outPixels[rowOff + x] = Color.rgb(gray, gray, gray)
            }
        }

        val depthBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        depthBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return RefinedDepthResult(
            depthBitmap = depthBitmap,
            nearMeters = nearM,
            farMeters = farM,
            hasMetricDepth = true
        )
    }

    /**
     * Fuses ALL captured ARCore Depth16 frames into a denoised, hole-filled consensus depth map
     * anchored to the sharpest RGB hero frame, then refines edges via joint bilateral upsampling.
     */
    fun fuseMultiFrameMetricDepth(
        colorBitmap: Bitmap,
        heroDepth: MetricDepthObservation?,
        allDepths: List<MetricDepthObservation>,
        stereoFallbackNeighbors: List<StereoNeighborObservation> = emptyList()
    ): RefinedDepthResult {
        val anchor = heroDepth ?: allDepths.firstOrNull()
        if (anchor == null || anchor.width <= 0 || anchor.height <= 0) {
            return estimateAllFramesStereoDepth(colorBitmap, stereoFallbackNeighbors)
        }
        if (allDepths.size <= 1) {
            return refineMetricDepthMap(
                colorBitmap = colorBitmap,
                rawDepthMm = anchor.rawDepthMm,
                depthWidth = anchor.width,
                depthHeight = anchor.height
            )
        }

        val w = anchor.width
        val h = anchor.height
        val size = w * h
        val depthSum = FloatArray(size)
        val weightSum = FloatArray(size)

        // First anchor with hero's direct depth observations so subject silhouette stays razor sharp
        for (i in 0 until size) {
            val mm = anchor.rawDepthMm[i].toInt() and 0xFFFF
            if (mm in 150..18000) {
                val anchorW = anchor.weight * 2.2f
                depthSum[i] = mm * anchorW
                weightSum[i] = anchorW
            }
        }

        // Fuse every additional sharp frame's depth buffer to fill holes and reduce sensor noise
        for (obs in allDepths) {
            if (obs === anchor || obs.width <= 0 || obs.height <= 0 || obs.rawDepthMm.size < obs.width * obs.height) {
                continue
            }
            val obsW = obs.width
            val obsH = obs.height
            val wBase = obs.weight.coerceAtLeast(0.1f)
            for (y in 0 until h) {
                val oy = (y * obsH / h).coerceIn(0, obsH - 1)
                val rowIdx = y * w
                val oRowIdx = oy * obsW
                for (x in 0 until w) {
                    val ox = (x * obsW / w).coerceIn(0, obsW - 1)
                    val mm = obs.rawDepthMm[oRowIdx + ox].toInt() and 0xFFFF
                    if (mm !in 150..18000) continue

                    val idx = rowIdx + x
                    val existingW = weightSum[idx]
                    if (existingW > 0f) {
                        val existingMm = depthSum[idx] / existingW
                        // Only refine already-valid hero depth from tightly aligned nearby frames within 10%
                        if (obs.isNearHero && abs(mm - existingMm) <= existingMm * 0.10f) {
                            depthSum[idx] += mm * wBase
                            weightSum[idx] += wBase
                        }
                    } else {
                        // Fill true depth holes using multi-view consensus
                        depthSum[idx] += mm * wBase
                        weightSum[idx] += wBase
                    }
                }
            }
        }

        val fusedMm = ShortArray(size)
        for (i in 0 until size) {
            fusedMm[i] = if (weightSum[i] > 0f) {
                (depthSum[i] / weightSum[i]).roundToInt().coerceIn(0, 65000).toShort()
            } else {
                0
            }
        }

        return refineMetricDepthMap(
            colorBitmap = colorBitmap,
            rawDepthMm = fusedMm,
            depthWidth = w,
            depthHeight = h
        )
    }

    /**
     * Locally aligns and blends up to 3 crisp sweep frames onto stationary objects in the hero RGB image,
     * reducing sensor noise and compression artifacts while rejecting any misaligned/moving pixels.
     */
    fun enhanceHeroFromMultiFrameSweep(
        heroBitmap: Bitmap,
        nearbyBitmaps: List<Bitmap>
    ): Bitmap {
        val sources = nearbyBitmaps.filter {
            it !== heroBitmap && !it.isRecycled && it.width == heroBitmap.width && it.height == heroBitmap.height
        }.take(2)
        if (sources.isEmpty()) return heroBitmap

        val w = heroBitmap.width
        val h = heroBitmap.height
        val heroPx = IntArray(w * h)
        heroBitmap.getPixels(heroPx, 0, w, 0, 0, w, h)

        val sumR = FloatArray(w * h)
        val sumG = FloatArray(w * h)
        val sumB = FloatArray(w * h)
        val heroWeight = 1.35f
        val wSum = FloatArray(w * h) { heroWeight }
        for (i in heroPx.indices) {
            val c = heroPx[i]
            sumR[i] = ((c shr 16) and 0xFF) * heroWeight
            sumG[i] = ((c shr 8) and 0xFF) * heroWeight
            sumB[i] = (c and 0xFF) * heroWeight
        }

        val nbPx = IntArray(w * h)
        val block = 32
        val search = 6

        for (nb in sources) {
            nb.getPixels(nbPx, 0, w, 0, 0, w, h)
            var by = 0
            while (by < h) {
                val bh = min(block, h - by)
                var bx = 0
                while (bx < w) {
                    val bw = min(block, w - bx)
                    // Find best integer shift (dx, dy) matching stationary detail inside this block
                    var bestDx = 0
                    var bestDy = 0
                    var bestCost = Int.MAX_VALUE
                    var bestSamples = 0
                    var dy = -search
                    while (dy <= search) {
                        var dx = -search
                        while (dx <= search) {
                            var cost = 0
                            var samples = 0
                            var sy = 4
                            while (sy < bh - 4) {
                                val hy = by + sy
                                val ny = (hy + dy).coerceIn(0, h - 1)
                                val hRow = hy * w
                                val nRow = ny * w
                                var sx = 4
                                while (sx < bw - 4) {
                                    val hx = bx + sx
                                    val nx = (hx + dx).coerceIn(0, w - 1)
                                    val hc = heroPx[hRow + hx]
                                    val nc = nbPx[nRow + nx]
                                    cost += abs(((hc shr 16) and 0xFF) - ((nc shr 16) and 0xFF)) +
                                        abs(((hc shr 8) and 0xFF) - ((nc shr 8) and 0xFF)) +
                                        abs((hc and 0xFF) - (nc and 0xFF))
                                    samples++
                                    sx += 6
                                }
                                sy += 6
                            }
                            if (samples > 0 && cost < bestCost) {
                                bestCost = cost
                                bestDx = dx
                                bestDy = dy
                                bestSamples = samples
                            }
                            dx += 2
                        }
                        dy += 2
                    }

                    // Gate out any block that shows residual skew, parallax shift, or blur mismatch
                    val avgBlockDiff = if (bestSamples > 0) bestCost.toFloat() / (bestSamples * 3f) else Float.MAX_VALUE
                    if (avgBlockDiff <= 12f) {
                        // Pixel-level strict consistency merge: cleans sensor grain on stationary regions, zero ghosting
                        for (py in by until by + bh) {
                            val ny = (py + bestDy).coerceIn(0, h - 1)
                            val hRow = py * w
                            val nRow = ny * w
                            for (px in bx until bx + bw) {
                                val nx = (px + bestDx).coerceIn(0, w - 1)
                                val idx = hRow + px
                                val hc = heroPx[idx]
                                val nc = nbPx[nRow + nx]
                                val nr = (nc shr 16) and 0xFF
                                val ng = (nc shr 8) and 0xFF
                                val nbCol = nc and 0xFF
                                val diffMax = max(
                                    abs(((hc shr 16) and 0xFF) - nr),
                                    max(abs(((hc shr 8) and 0xFF) - ng), abs((hc and 0xFF) - nbCol))
                                )
                                if (diffMax <= 14) {
                                    val wPix = 1f - (diffMax / 18f)
                                    sumR[idx] += nr * wPix
                                    sumG[idx] += ng * wPix
                                    sumB[idx] += nbCol * wPix
                                    wSum[idx] += wPix
                                }
                            }
                        }
                    }
                    bx += block
                }
                by += block
            }
        }

        val mergedPx = IntArray(w * h)
        for (i in mergedPx.indices) {
            val invW = 1f / wSum[i]
            val r = (sumR[i] * invW).roundToInt().coerceIn(0, 255)
            val g = (sumG[i] * invW).roundToInt().coerceIn(0, 255)
            val b = (sumB[i] * invW).roundToInt().coerceIn(0, 255)
            mergedPx[i] = Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(mergedPx, 0, w, 0, 0, w, h)
        }
    }

    /**
     * Estimates depth from ALL captured multi-angle frames (CameraX fallback or synthetic boost)
     * by accumulating confidence-weighted stereo disparity across horizontal and vertical waves.
     */
    fun estimateAllFramesStereoDepth(
        centerBitmap: Bitmap,
        neighbors: List<StereoNeighborObservation>
    ): RefinedDepthResult {
        val candidateNeighbors = neighbors.filter { it.bitmap !== centerBitmap }
        val axisAligned = candidateNeighbors.filter { nb ->
            val adx = abs(nb.dxRight)
            val ady = abs(nb.dyUp)
            (adx >= 0.008f && adx >= ady * 1.6f) || (ady >= 0.008f && ady >= adx * 1.6f)
        }
        val activeNeighbors = (if (axisAligned.size >= 2) axisAligned else candidateNeighbors)
            .sortedByDescending { (abs(it.dxRight) + abs(it.dyUp)) * it.weight }
            .take(4)

        if (activeNeighbors.isEmpty()) {
            return buildFallbackRadialDepth(centerBitmap, hasMetric = false)
        }

        val w = 128
        val h = 96
        val lumaCenter = bitmapToLumaGrid(centerBitmap, w, h)
        val dispAccum = FloatArray(w * h)
        val confAccum = FloatArray(w * h)

        for (nb in activeNeighbors) {
            val lumaNb = bitmapToLumaGrid(nb.bitmap, w, h)
            val horizontal = abs(nb.dxRight) >= abs(nb.dyUp)
            val (disp, conf) = computeAxisDisparity(lumaCenter, lumaNb, w, h, horizontal = horizontal)
            val frameWeight = nb.weight.coerceAtLeast(0.15f)
            for (i in dispAccum.indices) {
                val c = conf[i] * frameWeight
                if (c > 0.01f) {
                    dispAccum[i] += disp[i] * c
                    confAccum[i] += c
                }
            }
        }

        val rawDispGrid = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val centerBias = 1f - ((x - w * 0.5f) * (x - w * 0.5f) / (w * w * 0.42f) +
                    (y - h * 0.55f) * (y - h * 0.55f) / (h * h * 0.42f)).coerceIn(0f, 0.85f)
                val wSum = confAccum[idx]
                val blendedDisp = if (wSum > 0.05f) dispAccum[idx] / wSum else centerBias
                val bestConf = (wSum / max(1f, activeNeighbors.size * 0.35f)).coerceIn(0f, 1f)
                rawDispGrid[idx] = (blendedDisp * bestConf + centerBias * (1f - bestConf) * 0.65f)
                    .coerceIn(0.05f, 1f)
            }
        }

        // 3x3 median despike wipes out isolated stereo block outliers that cause skewed blobs
        val dispGrid = medianFilter3x3(rawDispGrid, w, h)

        val sampledDisp = ArrayList<Float>((w * h) / 4)
        for (i in dispGrid.indices step 4) {
            if (dispGrid[i] > 0f) sampledDisp.add(dispGrid[i])
        }
        val dLow: Float
        val dHigh: Float
        if (sampledDisp.size >= 16) {
            sampledDisp.sort()
            dLow = sampledDisp[(sampledDisp.size * 0.06f).toInt().coerceIn(0, sampledDisp.lastIndex)]
            dHigh = sampledDisp[(sampledDisp.size * 0.94f).toInt().coerceIn(0, sampledDisp.lastIndex)]
                .coerceAtLeast(dLow + 0.22f)
        } else {
            dLow = 0.05f
            dHigh = 0.85f
        }

        val nearM = 0.55f
        val farM = 4.5f
        val pseudoRawMm = ShortArray(w * h)
        for (i in dispGrid.indices) {
            val stretched = ((dispGrid[i] - dLow) / (dHigh - dLow)).coerceIn(0.05f, 1f)
            val invDepth = (1f / farM) + stretched * ((1f / nearM) - (1f / farM))
            val meters = (1f / invDepth).coerceIn(nearM, farM)
            pseudoRawMm[i] = (meters * 1000f).roundToInt().toShort()
        }

        val refined = refineMetricDepthMap(centerBitmap, pseudoRawMm, w, h)
        return refined.copy(hasMetricDepth = false)
    }

    /**
     * Estimates depth from two horizontal sweep frames (CameraX + IMU fallback when ARCore is absent).
     */
    fun estimateStereoDepthFromSweep(
        centerBitmap: Bitmap,
        neighborBitmap: Bitmap
    ): RefinedDepthResult = estimateMultiViewStereoDepthFromCircle(
        centerBitmap = centerBitmap,
        horizNeighbor = neighborBitmap,
        vertNeighbor = null
    )

    /**
     * Estimates depth from circular multi-view frames by pairing horizontal and vertical baseline
     * neighbors so both vertical and horizontal edges resolve cleanly in CameraX fallback mode.
     */
    fun estimateMultiViewStereoDepthFromCircle(
        centerBitmap: Bitmap,
        horizNeighbor: Bitmap?,
        vertNeighbor: Bitmap?
    ): RefinedDepthResult {
        if (horizNeighbor == null && vertNeighbor == null) {
            return buildFallbackRadialDepth(centerBitmap, hasMetric = false)
        }

        val w = 160
        val h = 120
        val lumaA = bitmapToLumaGrid(centerBitmap, w, h)
        val horizPair = horizNeighbor?.let { nb ->
            computeAxisDisparity(lumaA, bitmapToLumaGrid(nb, w, h), w, h, horizontal = true)
        }
        val vertPair = vertNeighbor?.let { nb ->
            computeAxisDisparity(lumaA, bitmapToLumaGrid(nb, w, h), w, h, horizontal = false)
        }

        val dispGrid = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val centerBias = 1f - ((x - w * 0.5f) * (x - w * 0.5f) / (w * w * 0.42f) +
                    (y - h * 0.55f) * (y - h * 0.55f) / (h * h * 0.42f)).coerceIn(0f, 0.85f)

                val hDisp = horizPair?.first?.get(idx) ?: 0f
                val hConf = horizPair?.second?.get(idx) ?: 0f
                val vDisp = vertPair?.first?.get(idx) ?: 0f
                val vConf = vertPair?.second?.get(idx) ?: 0f

                val totalConf = hConf + vConf
                val combinedDisp: Float
                val bestConf: Float
                if (horizPair != null && vertPair != null && totalConf > 0.05f) {
                    combinedDisp = (hDisp * hConf + vDisp * vConf) / totalConf
                    bestConf = max(hConf, vConf)
                } else if (horizPair != null) {
                    combinedDisp = hDisp
                    bestConf = hConf
                } else {
                    combinedDisp = vDisp
                    bestConf = vConf
                }
                dispGrid[idx] = (combinedDisp * bestConf + centerBias * (1f - bestConf) * 0.65f)
                    .coerceIn(0.05f, 1f)
            }
        }

        // Stretch disparity percentiles so gentle circular loops produce vivid 3D depth separation
        val sampledDisp = ArrayList<Float>((w * h) / 4)
        for (i in dispGrid.indices step 4) {
            if (dispGrid[i] > 0f) sampledDisp.add(dispGrid[i])
        }
        val dLow: Float
        val dHigh: Float
        if (sampledDisp.size >= 16) {
            sampledDisp.sort()
            dLow = sampledDisp[(sampledDisp.size * 0.05f).toInt().coerceIn(0, sampledDisp.lastIndex)]
            dHigh = sampledDisp[(sampledDisp.size * 0.95f).toInt().coerceIn(0, sampledDisp.lastIndex)]
                .coerceAtLeast(dLow + 0.12f)
        } else {
            dLow = 0.05f
            dHigh = 0.85f
        }

        // Convert normalized disparity to pseudo-metric meters (0.55m .. 4.5m) for RangeInverse GDepth compatibility
        val nearM = 0.55f
        val farM = 4.5f
        val pseudoRawMm = ShortArray(w * h)
        for (i in dispGrid.indices) {
            val stretched = ((dispGrid[i] - dLow) / (dHigh - dLow)).coerceIn(0.05f, 1f)
            val invDepth = (1f / farM) + stretched * ((1f / nearM) - (1f / farM))
            val meters = (1f / invDepth).coerceIn(nearM, farM)
            pseudoRawMm[i] = (meters * 1000f).roundToInt().toShort()
        }

        val refined = refineMetricDepthMap(centerBitmap, pseudoRawMm, w, h)
        return refined.copy(hasMetricDepth = false)
    }

    private fun bitmapToLumaGrid(bitmap: Bitmap, w: Int, h: Int): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        return FloatArray(w * h) { i ->
            val c = px[i]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
    }

    private fun computeAxisDisparity(
        lumaA: FloatArray,
        lumaB: FloatArray,
        w: Int,
        h: Int,
        horizontal: Boolean
    ): Pair<FloatArray, FloatArray> {
        val disp = FloatArray(w * h)
        val conf = FloatArray(w * h)
        val maxShift = 12
        val block = 2
        val minY = if (horizontal) block else maxShift + block
        val maxY = if (horizontal) h - block else h - maxShift - block
        val minX = if (horizontal) maxShift + block else block
        val maxX = if (horizontal) w - maxShift - block else w - block

        for (y in minY until maxY) {
            for (x in minX until maxX) {
                // Measure local texture contrast so flat/smooth stationary surfaces don't invent bumpy noise
                var lumaSum = 0f
                for (by in -block..block) {
                    val rowA = (y + by) * w
                    for (bx in -block..block) lumaSum += lumaA[rowA + x + bx]
                }
                val lumaMean = lumaSum / 25f
                var lumaDev = 0f
                for (by in -block..block) {
                    val rowA = (y + by) * w
                    for (bx in -block..block) lumaDev += abs(lumaA[rowA + x + bx] - lumaMean)
                }
                val avgContrast = lumaDev / 25f
                val textureWeight = ((avgContrast - 2.2f) / 6.0f).coerceIn(0f, 1f)
                if (textureWeight <= 0.02f) continue

                var bestShift = 0
                var bestSad = Float.MAX_VALUE
                var secondSad = Float.MAX_VALUE
                for (s in -maxShift..maxShift) {
                    var sad = 0f
                    for (by in -block..block) {
                        val rowA = (y + by) * w
                        val rowB = if (horizontal) (y + by) * w else (y + by + s) * w
                        val xShift = if (horizontal) s else 0
                        for (bx in -block..block) {
                            sad += abs(lumaA[rowA + x + bx] - lumaB[rowB + x + bx + xShift])
                        }
                    }
                    if (sad < bestSad) {
                        secondSad = bestSad
                        bestSad = sad
                        bestShift = s
                    } else if (sad < secondSad) {
                        secondSad = sad
                    }
                }
                val idx = y * w + x
                val avgBestSad = bestSad / 25f
                if (avgBestSad <= 18f) {
                    val matchSharpness = ((secondSad - bestSad) / (bestSad + 18f)).coerceIn(0f, 1f)
                    val qualityWeight = (1f - (avgBestSad / 22f)).coerceIn(0.15f, 1f)
                    conf[idx] = matchSharpness * textureWeight * qualityWeight
                    disp[idx] = (abs(bestShift).toFloat() / maxShift.toFloat()).coerceIn(0f, 1f)
                }
            }
        }
        return disp to conf
    }

    private fun medianFilter3x3(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = src.copyOf()
        val win = FloatArray(9)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var k = 0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    for (dx in -1..1) {
                        win[k++] = src[row + x + dx]
                    }
                }
                win.sort()
                out[y * w + x] = win[4]
            }
        }
        return out
    }

    private fun buildFallbackRadialDepth(colorBitmap: Bitmap, hasMetric: Boolean): RefinedDepthResult {
        val outW = 256
        val outH = (256f * colorBitmap.height / max(1, colorBitmap.width)).roundToInt().coerceIn(128, 384)
        val pixels = IntArray(outW * outH)
        for (y in 0 until outH) {
            val ny = (y.toFloat() / outH - 0.55f) * 2f
            for (x in 0 until outW) {
                val nx = (x.toFloat() / outW - 0.5f) * 2f
                val dist = (nx * nx * 0.7f + ny * ny * 0.7f).coerceIn(0f, 1f)
                val v = ((1f - dist * 0.75f) * 255f).roundToInt().coerceIn(20, 255)
                pixels[y * outW + x] = Color.rgb(v, v, v)
            }
        }
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return RefinedDepthResult(bmp, nearMeters = 0.6f, farMeters = 4.5f, hasMetricDepth = hasMetric)
    }

    private fun fillDepthHoles(rawMeters: FloatArray, w: Int, h: Int, fallbackFar: Float): FloatArray {
        val out = rawMeters.copyOf()
        // Horizontal forward + backward pass
        for (y in 0 until h) {
            var lastValid = 0f
            for (x in 0 until w) {
                val idx = y * w + x
                if (out[idx] > 0f) lastValid = out[idx] else if (lastValid > 0f) out[idx] = lastValid
            }
            lastValid = 0f
            for (x in w - 1 downTo 0) {
                val idx = y * w + x
                if (out[idx] > 0f) lastValid = out[idx] else if (lastValid > 0f) out[idx] = lastValid
            }
        }
        // Vertical pass + fallback
        for (x in 0 until w) {
            var lastValid = 0f
            for (y in 0 until h) {
                val idx = y * w + x
                if (out[idx] > 0f) lastValid = out[idx] else if (lastValid > 0f) out[idx] = lastValid
            }
            lastValid = 0f
            for (y in h - 1 downTo 0) {
                val idx = y * w + x
                if (out[idx] > 0f) {
                    lastValid = out[idx]
                } else {
                    out[idx] = if (lastValid > 0f) lastValid else fallbackFar
                }
            }
        }
        return out
    }

    /**
     * Encodes primary RGB image as a high-quality standard JPEG with embedded Google GDepth XMP metadata.
     * Standard Android Photos / Google Photos displays it as a normal flat JPEG; 3d Camera reads the depth map.
     */
    fun encodeDynamicDepthJpeg(
        colorBitmap: Bitmap,
        depthBitmap: Bitmap,
        nearMeters: Float,
        farMeters: Float
    ): ByteArray {
        val colorStream = ByteArrayOutputStream()
        colorBitmap.compress(Bitmap.CompressFormat.JPEG, 94, colorStream)
        val primaryJpeg = colorStream.toByteArray()

        val depthStream = ByteArrayOutputStream()
        depthBitmap.compress(Bitmap.CompressFormat.JPEG, 85, depthStream)
        val depthBase64 = Base64.encodeToString(depthStream.toByteArray(), Base64.NO_WRAP)

        val fullXmp = buildGDepthXmpPacket(
            depthBase64 = depthBase64,
            nearMeters = nearMeters,
            farMeters = farMeters
        )

        val fullXmpBytes = fullXmp.toByteArray(StandardCharsets.UTF_8)
        val segments = if (fullXmpBytes.size <= MAX_STD_XMP_BYTES) {
            listOf(buildApp1Segment(STD_XMP_HEADER.toByteArray(StandardCharsets.US_ASCII) + fullXmpBytes))
        } else {
            // Split into standard XMP + Adobe Extended XMP APP1 chunks
            val extXml = buildExtendedXmpPacket(depthBase64)
            val extBytes = extXml.toByteArray(StandardCharsets.UTF_8)
            val guid = md5Hex(extBytes).uppercase()
            val stdXml = buildStandardStubXmpPacket(guid, nearMeters, farMeters)
            val stdSegment = buildApp1Segment(
                STD_XMP_HEADER.toByteArray(StandardCharsets.US_ASCII) + stdXml.toByteArray(StandardCharsets.UTF_8)
            )
            val list = mutableListOf(stdSegment)
            var offset = 0
            while (offset < extBytes.size) {
                val len = min(EXT_CHUNK_PAYLOAD, extBytes.size - offset)
                val header = ByteArrayOutputStream().apply {
                    write(EXT_XMP_HEADER.toByteArray(StandardCharsets.US_ASCII))
                    write(guid.toByteArray(StandardCharsets.US_ASCII))
                    write(intToBigEndianBytes(extBytes.size))
                    write(intToBigEndianBytes(offset))
                    write(extBytes, offset, len)
                }.toByteArray()
                list.add(buildApp1Segment(header))
                offset += len
            }
            list
        }

        // Insert right after SOI (FF D8)
        if (primaryJpeg.size < 4 || (primaryJpeg[0].toInt() and 0xFF) != 0xFF || (primaryJpeg[1].toInt() and 0xFF) != 0xD8) {
            return primaryJpeg
        }

        val out = ByteArrayOutputStream(primaryJpeg.size + fullXmpBytes.size + 256)
        out.write(primaryJpeg, 0, 2) // FF D8
        for (seg in segments) {
            out.write(seg)
        }
        out.write(primaryJpeg, 2, primaryJpeg.size - 2)
        return out.toByteArray()
    }

    /**
     * Parses any JPEG byte array, extracting the orientation-corrected primary RGB bitmap
     * and any embedded Google GDepth XMP depth map (supporting both standard and extended XMP).
     */
    fun decodeDynamicDepthJpeg(jpegBytes: ByteArray): DecodedSpatialPhoto? {
        val rawBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val colorBitmap = applyExifOrientation(rawBmp, jpegBytes)

        val xmpPackets = extractXmpStrings(jpegBytes)
        val combined = xmpPackets.joinToString("\n")
        val depthBase64 = extractXmpAttrOrTag(combined, "GDepth:Data")
        val nearStr = extractXmpAttrOrTag(combined, "GDepth:Near")
        val farStr = extractXmpAttrOrTag(combined, "GDepth:Far")

        val nearM = nearStr?.toFloatOrNull() ?: 0.5f
        val farM = farStr?.toFloatOrNull() ?: 5.0f

        val depthBitmap = if (!depthBase64.isNullOrBlank()) {
            runCatching {
                val cleanB64 = depthBase64.replace("\\s".toRegex(), "")
                val dBytes = Base64.decode(cleanB64, Base64.DEFAULT)
                val rawDepth = BitmapFactory.decodeByteArray(dBytes, 0, dBytes.size)
                rawDepth?.let { applyExifOrientation(it, jpegBytes, isDepth = true) }
            }.getOrNull()
        } else {
            null
        }

        return DecodedSpatialPhoto(
            colorBitmap = colorBitmap,
            depthBitmap = depthBitmap,
            nearMeters = nearM,
            farMeters = farM,
            hasDepthMap = depthBitmap != null
        )
    }

    /**
     * Generates a universal Side-by-Side (SBS) stereoscopic 3D JPEG (Left | Right) for Meta Quest / VR viewers.
     */
    fun synthesizeSideBySideStereoJpeg(
        colorBitmap: Bitmap,
        depthBitmap: Bitmap,
        maxEyeWidth: Int = 1440
    ): ByteArray {
        val scale = min(1f, maxEyeWidth.toFloat() / colorBitmap.width)
        val eyeW = (colorBitmap.width * scale).roundToInt().coerceAtLeast(16)
        val eyeH = (colorBitmap.height * scale).roundToInt().coerceAtLeast(16)

        val scaledColor = Bitmap.createScaledBitmap(colorBitmap, eyeW, eyeH, true)
        val scaledDepth = Bitmap.createScaledBitmap(depthBitmap, eyeW, eyeH, true)

        val colorPx = IntArray(eyeW * eyeH)
        val depthPx = IntArray(eyeW * eyeH)
        scaledColor.getPixels(colorPx, 0, eyeW, 0, 0, eyeW, eyeH)
        scaledDepth.getPixels(depthPx, 0, eyeW, 0, 0, eyeW, eyeH)

        if (scaledColor !== colorBitmap) scaledColor.recycle()
        if (scaledDepth !== depthBitmap) scaledDepth.recycle()

        val maxShiftPx = eyeW * 0.026f
        val leftEye = renderWarpedEye(colorPx, depthPx, eyeW, eyeH, -maxShiftPx)
        val rightEye = renderWarpedEye(colorPx, depthPx, eyeW, eyeH, +maxShiftPx)

        val sbsBitmap = Bitmap.createBitmap(eyeW * 2, eyeH, Bitmap.Config.ARGB_8888)
        sbsBitmap.setPixels(leftEye, 0, eyeW, 0, 0, eyeW, eyeH)
        sbsBitmap.setPixels(rightEye, 0, eyeW, eyeW, 0, eyeW, eyeH)

        val out = ByteArrayOutputStream()
        sbsBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        sbsBitmap.recycle()
        return out.toByteArray()
    }

    private fun renderWarpedEye(
        colorPx: IntArray,
        depthPx: IntArray,
        w: Int,
        h: Int,
        eyeShiftPx: Float
    ): IntArray {
        val out = IntArray(w * h)
        // Backward-mapped ray search with foreground priority gives crisp edges without holes
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var bestColor = colorPx[row + x]
                var bestDepth = -1
                // Search nearby source pixels that could project to target x
                val searchRadius = abs(eyeShiftPx).roundToInt() + 2
                val minSx = max(0, x - searchRadius)
                val maxSx = min(w - 1, x + searchRadius)
                for (sx in minSx..maxSx) {
                    val dGray = Color.red(depthPx[row + sx])
                    val dNorm = dGray / 255f
                    val projectedX = sx + eyeShiftPx * (dNorm - 0.35f)
                    if (abs(projectedX - x) <= 0.75f && dGray >= bestDepth) {
                        bestDepth = dGray
                        bestColor = colorPx[row + sx]
                    }
                }
                if (bestDepth < 0) {
                    // Fallback sample at background shift
                    val sx = (x - eyeShiftPx * -0.25f).roundToInt().coerceIn(0, w - 1)
                    bestColor = colorPx[row + sx]
                }
                out[row + x] = bestColor
            }
        }
        return out
    }

    private fun buildGDepthXmpPacket(depthBase64: String, nearMeters: Float, farMeters: Float): String {
        return """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GDepth="http://ns.google.com/photos/1.0/depthmap/"
    GDepth:Format="RangeInverse"
    GDepth:Near="$nearMeters"
    GDepth:Far="$farMeters"
    GDepth:Mime="image/jpeg"
    GDepth:Data="$depthBase64"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    private fun buildStandardStubXmpPacket(guid: String, nearMeters: Float, farMeters: Float): String {
        return """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:xmpNote="http://ns.adobe.com/xmp/note/"
    xmlns:GDepth="http://ns.google.com/photos/1.0/depthmap/"
    xmpNote:HasExtendedXMP="$guid"
    GDepth:Format="RangeInverse"
    GDepth:Near="$nearMeters"
    GDepth:Far="$farMeters"
    GDepth:Mime="image/jpeg"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    private fun buildExtendedXmpPacket(depthBase64: String): String {
        return """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GDepth="http://ns.google.com/photos/1.0/depthmap/"
    GDepth:Data="$depthBase64"/>
 </rdf:RDF>
</x:xmpmeta>"""
    }

    private fun buildApp1Segment(payload: ByteArray): ByteArray {
        val segLen = payload.size + 2
        val out = ByteArrayOutputStream(segLen + 2)
        out.write(0xFF)
        out.write(0xE1)
        out.write((segLen shr 8) and 0xFF)
        out.write(segLen and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private fun extractXmpStrings(jpegBytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val extChunks = mutableMapOf<Int, ByteArray>()
        var pos = 2
        val stdPrefix = STD_XMP_HEADER.toByteArray(StandardCharsets.US_ASCII)
        val extPrefix = EXT_XMP_HEADER.toByteArray(StandardCharsets.US_ASCII)

        while (pos + 4 < jpegBytes.size) {
            if ((jpegBytes[pos].toInt() and 0xFF) != 0xFF) break
            val marker = jpegBytes[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break // SOS or EOI
            val len = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > jpegBytes.size) break

            if (marker == 0xE1) {
                val dataStart = pos + 4
                val dataLen = len - 2
                if (dataLen > stdPrefix.size && matchesPrefix(jpegBytes, dataStart, stdPrefix)) {
                    result.add(String(jpegBytes, dataStart + stdPrefix.size, dataLen - stdPrefix.size, StandardCharsets.UTF_8))
                } else if (dataLen > extPrefix.size + 40 && matchesPrefix(jpegBytes, dataStart, extPrefix)) {
                    val headerOffset = dataStart + extPrefix.size + 32
                    val chunkOffset = readBigEndianInt(jpegBytes, headerOffset + 4)
                    val chunkStart = headerOffset + 8
                    val chunkLen = (dataStart + dataLen) - chunkStart
                    if (chunkLen > 0) {
                        extChunks[chunkOffset] = jpegBytes.copyOfRange(chunkStart, chunkStart + chunkLen)
                    }
                }
            }
            pos += 2 + len
        }

        if (extChunks.isNotEmpty()) {
            val assembled = ByteArrayOutputStream()
            extChunks.toSortedMap().values.forEach { assembled.write(it) }
            result.add(assembled.toString(StandardCharsets.UTF_8.name()))
        }
        return result
    }

    private fun extractXmpAttrOrTag(xml: String, key: String): String? {
        val attrRegex = Regex("""$key\s*=\s*"([^"]*)"""")
        attrRegex.find(xml)?.groupValues?.getOrNull(1)?.let { return it }
        val tagRegex = Regex("""<$key[^>]*>([^<]*)</$key>""")
        return tagRegex.find(xml)?.groupValues?.getOrNull(1)
    }

    private fun matchesPrefix(data: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > data.size) return false
        for (i in prefix.indices) {
            if (data[offset + i] != prefix[i]) return false
        }
        return true
    }

    private fun intToBigEndianBytes(v: Int): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    private fun readBigEndianInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun applyExifOrientation(bitmap: Bitmap, jpegBytes: ByteArray, isDepth: Boolean = false): Bitmap {
        if (isDepth) return bitmap
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(jpegBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}
