package com.example.viewer

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Battery-efficient OpenGL ES 2.0 3D perspective renderer (RENDERMODE_WHEN_DIRTY) that turns
 * a color photo + GDepth map into a cohesive 3D relief scene you can tilt, rotate, and zoom
 * in 3D space without rubbery image mutation.
 */
class SpatialParallaxSurfaceView(context: Context) : GLSurfaceView(context) {

    private val renderer = ParallaxMeshRenderer { requestRender() }

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setSpatialTextures(colorBitmap: Bitmap, depthBitmap: Bitmap?) {
        queueEvent {
            renderer.updateTextures(colorBitmap, depthBitmap)
        }
        requestRender()
    }

    fun setTiltOffset(tiltX: Float, tiltY: Float) {
        if (renderer.setTilt(tiltX, tiltY)) {
            requestRender()
        }
    }

    fun setRollDegrees(rollDeg: Float) {
        if (renderer.setRollDegrees(rollDeg)) {
            requestRender()
        }
    }

    fun setZoomAndPan(zoom: Float, panX: Float, panY: Float) {
        if (renderer.setZoomAndPan(zoom, panX, panY)) {
            requestRender()
        }
    }

    private class ParallaxMeshRenderer(
        private val onRequestNextFrame: () -> Unit
    ) : Renderer {

        @Volatile
        private var targetTiltX = 0f
        @Volatile
        private var targetTiltY = 0f
        @Volatile
        private var targetRollDeg = 0f
        @Volatile
        private var targetZoom = 1f
        @Volatile
        private var targetPanX = 0f
        @Volatile
        private var targetPanY = 0f

        private var currentTiltX = 0f
        private var currentTiltY = 0f
        private var currentRollDeg = 0f
        private var currentZoom = 1f
        private var currentPanX = 0f
        private var currentPanY = 0f

        private var pendingColor: Bitmap? = null
        private var pendingDepth: Bitmap? = null
        private var pendingHasDepth = false
        private var hasDepthMap = false
        private var imageAspect = 0.75f
        private var viewAspect = 0.56f

        private var program = 0
        private var colorTexId = 0
        private var depthTexId = 0

        private var vboId = 0
        private var iboId = 0
        private var indexCount = 0

        private var aPosLoc = 0
        private var aUvLoc = 0
        private var uColorTexLoc = 0
        private var uDepthTexLoc = 0
        private var uMvpMatrixLoc = 0
        private var uHasDepthLoc = 0
        private var uCardExtentsLoc = 0
        private var uReliefDepthLoc = 0
        private var uZoomLoc = 0
        private var uPanLoc = 0

        private val projMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val modelMatrix = FloatArray(16)
        private val mvMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        fun setTilt(tx: Float, ty: Float): Boolean {
            val clampedX = tx.coerceIn(-1f, 1f)
            val clampedY = ty.coerceIn(-1f, 1f)
            val changed = abs(clampedX - targetTiltX) > 0.002f || abs(clampedY - targetTiltY) > 0.002f
            targetTiltX = clampedX
            targetTiltY = clampedY
            return changed
        }

        fun setRollDegrees(rollDeg: Float): Boolean {
            val wrapped = SpatialViewportState.wrapRollDegrees(rollDeg)
            val delta = abs(((wrapped - targetRollDeg + 540f) % 360f) - 180f)
            val changed = delta > 0.08f
            targetRollDeg = wrapped
            return changed
        }

        fun setZoomAndPan(zoom: Float, panX: Float, panY: Float): Boolean {
            val clampedZoom = zoom.coerceIn(1f, 4f)
            val clampedPanX = if (clampedZoom <= 1.01f) 0f else panX.coerceIn(-4f, 4f)
            val clampedPanY = if (clampedZoom <= 1.01f) 0f else panY.coerceIn(-4f, 4f)
            val changed = abs(clampedZoom - targetZoom) > 0.002f ||
                abs(clampedPanX - targetPanX) > 0.002f ||
                abs(clampedPanY - targetPanY) > 0.002f
            targetZoom = clampedZoom
            targetPanX = clampedPanX
            targetPanY = clampedPanY
            return changed
        }

        fun updateTextures(color: Bitmap, depth: Bitmap?) {
            pendingColor = color
            pendingDepth = depth
            pendingHasDepth = depth != null
            imageAspect = color.width.toFloat() / color.height.coerceAtLeast(1)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.039f, 0.051f, 0.071f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)

            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vShader)
                GLES20.glAttachShader(it, fShader)
                GLES20.glLinkProgram(it)
            }

            aPosLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aUvLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uColorTexLoc = GLES20.glGetUniformLocation(program, "uColorTex")
            uDepthTexLoc = GLES20.glGetUniformLocation(program, "uDepthTex")
            uMvpMatrixLoc = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            uHasDepthLoc = GLES20.glGetUniformLocation(program, "uHasDepth")
            uCardExtentsLoc = GLES20.glGetUniformLocation(program, "uCardExtents")
            uReliefDepthLoc = GLES20.glGetUniformLocation(program, "uReliefDepth")
            uZoomLoc = GLES20.glGetUniformLocation(program, "uZoom")
            uPanLoc = GLES20.glGetUniformLocation(program, "uPan")

            val texIds = IntArray(2)
            GLES20.glGenTextures(2, texIds, 0)
            colorTexId = texIds[0]
            depthTexId = texIds[1]

            buildGridMesh(cols = 112, rows = 148)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewAspect = (width.toFloat() / height.coerceAtLeast(1)).coerceAtLeast(0.1f)
            onRequestNextFrame()
        }

        override fun onDrawFrame(gl: GL10?) {
            pendingColor?.let { bmp ->
                if (!bmp.isRecycled) {
                    uploadTexture(colorTexId, bmp)
                    hasDepthMap = pendingHasDepth
                }
                pendingColor = null
            }
            val pendingD = pendingDepth
            if (pendingD != null) {
                if (!pendingD.isRecycled) {
                    uploadTexture(depthTexId, pendingD)
                    hasDepthMap = true
                }
                pendingDepth = null
            }

            currentTiltX += (targetTiltX - currentTiltX) * 0.30f
            currentTiltY += (targetTiltY - currentTiltY) * 0.30f
            val rollDelta = ((targetRollDeg - currentRollDeg + 540f) % 360f) - 180f
            currentRollDeg = SpatialViewportState.wrapRollDegrees(currentRollDeg + rollDelta * 0.34f)
            currentZoom += (targetZoom - currentZoom) * 0.36f
            currentPanX += (targetPanX - currentPanX) * 0.36f
            currentPanY += (targetPanY - currentPanY) * 0.36f

            if (abs(targetTiltX - currentTiltX) < 0.001f) currentTiltX = targetTiltX
            if (abs(targetTiltY - currentTiltY) < 0.001f) currentTiltY = targetTiltY
            if (abs(rollDelta) < 0.05f) currentRollDeg = targetRollDeg
            if (abs(targetZoom - currentZoom) < 0.001f) currentZoom = targetZoom
            if (abs(targetPanX - currentPanX) < 0.001f) currentPanX = targetPanX
            if (abs(targetPanY - currentPanY) < 0.001f) currentPanY = targetPanY

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (colorTexId == 0) return

            GLES20.glUseProgram(program)

            // True 3D perspective camera framing the image plane at Z = 0
            val fovDeg = 40f
            val eyeZ = 2.85f
            val tanHalfFovY = tan(Math.toRadians(fovDeg * 0.5)).toFloat()
            val frustumHalfH = eyeZ * tanHalfFovY
            val frustumHalfW = frustumHalfH * viewAspect

            val baseHalfW: Float
            val baseHalfH: Float
            if (imageAspect > viewAspect) {
                baseHalfW = frustumHalfW
                baseHalfH = frustumHalfW / imageAspect
            } else {
                baseHalfH = frustumHalfH
                baseHalfW = frustumHalfH * imageAspect
            }

            // Slight pullback when actively tilted in 3D so perspective corners stay framed cleanly
            val tiltIntensity = max(abs(currentTiltX), abs(currentTiltY))
            val cardScale = 1.0f - 0.07f * tiltIntensity

            Matrix.perspectiveM(projMatrix, 0, fovDeg, viewAspect, 0.5f, 12f)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)

            Matrix.setIdentityM(modelMatrix, 0)
            // Apply screen-plane pitch & yaw first in view orientation, then 3D in-plane roll
            Matrix.rotateM(modelMatrix, 0, currentTiltY * 22f, 1f, 0f, 0f)
            Matrix.rotateM(modelMatrix, 0, currentTiltX * 26f, 0f, 1f, 0f)
            Matrix.rotateM(modelMatrix, 0, -currentRollDeg, 0f, 0f, 1f)
            Matrix.scaleM(modelMatrix, 0, cardScale, cardScale, 1f)

            Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

            val reliefDepth = if (hasDepthMap) 0.25f * min(baseHalfW, baseHalfH) else 0f
            val maxPanX = if (currentZoom <= 1.01f) 0f else ((baseHalfW / frustumHalfW) * currentZoom - 1.0f).coerceAtLeast(0f)
            val maxPanY = if (currentZoom <= 1.01f) 0f else ((baseHalfH / frustumHalfH) * currentZoom - 1.0f).coerceAtLeast(0f)
            val effectivePanX = currentPanX.coerceIn(-maxPanX, maxPanX)
            val effectivePanY = currentPanY.coerceIn(-maxPanY, maxPanY)

            GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
            GLES20.glUniform1f(uHasDepthLoc, if (hasDepthMap) 1.0f else 0.0f)
            GLES20.glUniform2f(uCardExtentsLoc, baseHalfW, baseHalfH)
            GLES20.glUniform1f(uReliefDepthLoc, reliefDepth)
            GLES20.glUniform1f(uZoomLoc, currentZoom)
            GLES20.glUniform2f(uPanLoc, effectivePanX, effectivePanY)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexId)
            GLES20.glUniform1i(uColorTexLoc, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
            GLES20.glUniform1i(uDepthTexLoc, 1)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            val stride = 4 * 4
            GLES20.glEnableVertexAttribArray(aPosLoc)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, stride, 0)
            GLES20.glEnableVertexAttribArray(aUvLoc)
            GLES20.glVertexAttribPointer(aUvLoc, 2, GLES20.GL_FLOAT, false, stride, 2 * 4)

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iboId)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

            GLES20.glDisableVertexAttribArray(aPosLoc)
            GLES20.glDisableVertexAttribArray(aUvLoc)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

            // Only schedule another GPU frame while converging toward target 3D orientation or zoom/pan
            val rollStillMoving = abs(((targetRollDeg - currentRollDeg + 540f) % 360f) - 180f) > 0.08f
            val stillConverging = abs(targetTiltX - currentTiltX) > 0.002f ||
                abs(targetTiltY - currentTiltY) > 0.002f ||
                rollStillMoving ||
                abs(targetZoom - currentZoom) > 0.002f ||
                abs(targetPanX - currentPanX) > 0.002f ||
                abs(targetPanY - currentPanY) > 0.002f
            if (stillConverging) {
                onRequestNextFrame()
            }
        }

        private fun uploadTexture(texId: Int, bitmap: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }

        private fun buildGridMesh(cols: Int, rows: Int) {
            val vertexCount = (cols + 1) * (rows + 1)
            val vertices = FloatArray(vertexCount * 4)
            var vIdx = 0
            for (r in 0..rows) {
                val v = r.toFloat() / rows
                val y = 1f - 2f * v
                for (c in 0..cols) {
                    val u = c.toFloat() / cols
                    val x = -1f + 2f * u
                    vertices[vIdx++] = x
                    vertices[vIdx++] = y
                    vertices[vIdx++] = u
                    vertices[vIdx++] = v
                }
            }

            val numQuads = cols * rows
            indexCount = numQuads * 6
            val indices = ShortArray(indexCount)
            var iIdx = 0
            val stride = cols + 1
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val topLeft = (r * stride + c).toShort()
                    val topRight = (topLeft + 1).toShort()
                    val bottomLeft = ((r + 1) * stride + c).toShort()
                    val bottomRight = (bottomLeft + 1).toShort()
                    indices[iIdx++] = topLeft
                    indices[iIdx++] = bottomLeft
                    indices[iIdx++] = topRight
                    indices[iIdx++] = topRight
                    indices[iIdx++] = bottomLeft
                    indices[iIdx++] = bottomRight
                }
            }

            val vBuf: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).apply { position(0) }
            val iBuf: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer().put(indices).apply { position(0) }

            val buffers = IntArray(2)
            GLES20.glGenBuffers(2, buffers, 0)
            vboId = buffers[0]
            iboId = buffers[1]

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, vBuf, GLES20.GL_STATIC_DRAW)

            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iboId)
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, iBuf, GLES20.GL_STATIC_DRAW)

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        }

        private fun compileShader(type: Int, code: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            return shader
        }

        companion object {
            private const val VERTEX_SHADER = """
                attribute vec2 aPosition;
                attribute vec2 aTexCoord;
                uniform sampler2D uDepthTex;
                uniform mat4 uMvpMatrix;
                uniform float uHasDepth;
                uniform vec2 uCardExtents;
                uniform float uReliefDepth;
                uniform float uZoom;
                uniform vec2 uPan;
                varying vec2 vTexCoord;

                float sampleSmoothDepth(vec2 uv) {
                    float c = texture2D(uDepthTex, uv).r;
                    float o = 0.0065;
                    float n = texture2D(uDepthTex, clamp(uv + vec2(0.0, -o), 0.0, 1.0)).r;
                    float s = texture2D(uDepthTex, clamp(uv + vec2(0.0,  o), 0.0, 1.0)).r;
                    float e = texture2D(uDepthTex, clamp(uv + vec2( o, 0.0), 0.0, 1.0)).r;
                    float w = texture2D(uDepthTex, clamp(uv + vec2(-o, 0.0), 0.0, 1.0)).r;
                    return c * 0.44 + (n + s + e + w) * 0.14;
                }

                void main() {
                    vTexCoord = aTexCoord;
                    float d = (uHasDepth > 0.5) ? sampleSmoothDepth(aTexCoord) : 0.0;
                    // Anchor only the outer 2.5% perimeter onto the 3D plane so tilted borders stay crisp
                    float borderX = smoothstep(0.0, 0.025, aTexCoord.x) * smoothstep(1.0, 0.975, aTexCoord.x);
                    float borderY = smoothstep(0.0, 0.025, aTexCoord.y) * smoothstep(1.0, 0.975, aTexCoord.y);
                    float borderMask = min(borderX, borderY);

                    float localX = aPosition.x * uCardExtents.x;
                    float localY = aPosition.y * uCardExtents.y;
                    float localZ = (d - 0.36) * uReliefDepth * borderMask;

                    vec4 clip = uMvpMatrix * vec4(localX, localY, localZ, 1.0);
                    clip.xy = clip.xy * uZoom + uPan * clip.w;
                    gl_Position = clip;
                }
            """

            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uColorTex;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(uColorTex, vTexCoord);
                }
            """
        }
    }
}
