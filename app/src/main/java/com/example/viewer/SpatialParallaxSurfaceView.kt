package com.example.viewer

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Battery-efficient OpenGL ES 2.0 renderer (RENDERMODE_WHEN_DIRTY) that turns a color photo +
 * GDepth map into a vivid, high-depth 3D parallax view driven by small phone tilts or finger drag.
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

    private class ParallaxMeshRenderer(
        private val onRequestNextFrame: () -> Unit
    ) : Renderer {

        @Volatile
        private var targetTiltX = 0f
        @Volatile
        private var targetTiltY = 0f

        private var currentTiltX = 0f
        private var currentTiltY = 0f

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
        private var uTiltLoc = 0
        private var uHasDepthLoc = 0
        private var uScaleLoc = 0

        fun setTilt(tx: Float, ty: Float): Boolean {
            val clampedX = tx.coerceIn(-1f, 1f)
            val clampedY = ty.coerceIn(-1f, 1f)
            val changed = abs(clampedX - targetTiltX) > 0.002f || abs(clampedY - targetTiltY) > 0.002f
            targetTiltX = clampedX
            targetTiltY = clampedY
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
            uTiltLoc = GLES20.glGetUniformLocation(program, "uTilt")
            uHasDepthLoc = GLES20.glGetUniformLocation(program, "uHasDepth")
            uScaleLoc = GLES20.glGetUniformLocation(program, "uAspectScale")

            val texIds = IntArray(2)
            GLES20.glGenTextures(2, texIds, 0)
            colorTexId = texIds[0]
            depthTexId = texIds[1]

            buildGridMesh(cols = 112, rows = 148)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewAspect = width.toFloat() / height.coerceAtLeast(1)
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

            currentTiltX += (targetTiltX - currentTiltX) * 0.28f
            currentTiltY += (targetTiltY - currentTiltY) * 0.28f

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (colorTexId == 0) return

            GLES20.glUseProgram(program)

            val scaleX: Float
            val scaleY: Float
            if (imageAspect > viewAspect) {
                scaleX = 1.0f
                scaleY = viewAspect / imageAspect
            } else {
                scaleX = imageAspect / viewAspect
                scaleY = 1.0f
            }
            // Overscan keeps edge-pinning zone cleanly outside the visible viewport so edges don't warp
            val overscan = if (hasDepthMap) 1.16f else 1.0f
            GLES20.glUniform2f(uScaleLoc, scaleX * overscan, scaleY * overscan)
            GLES20.glUniform2f(uTiltLoc, currentTiltX, currentTiltY)
            GLES20.glUniform1f(uHasDepthLoc, if (hasDepthMap) 1.0f else 0.0f)

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

            // Only schedule another GPU frame while converging toward target tilt
            if (abs(targetTiltX - currentTiltX) > 0.002f || abs(targetTiltY - currentTiltY) > 0.002f) {
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
                uniform vec2 uTilt;
                uniform float uHasDepth;
                uniform vec2 uAspectScale;
                varying vec2 vTexCoord;

                void main() {
                    vTexCoord = aTexCoord;
                    float d = (uHasDepth > 0.5) ? texture2D(uDepthTex, aTexCoord).r : 0.0;
                    // Smooth monotonic border anchor (0..6%) keeps max compression < 1.0 so foreground edges never fold
                    float edgeX = smoothstep(0.0, 0.060, aTexCoord.x) * smoothstep(1.0, 0.940, aTexCoord.x);
                    float edgeY = smoothstep(0.0, 0.060, aTexCoord.y) * smoothstep(1.0, 0.940, aTexCoord.y);
                    float edgePin = min(edgeX, edgeY);

                    float parallaxAmp = 0.175 * uHasDepth * edgePin;
                    float centeredDepth = d - 0.36;
                    vec2 offset = vec2(uTilt.x, -uTilt.y) * centeredDepth * parallaxAmp;

                    vec2 pos = (aPosition + offset) * uAspectScale;
                    float zOrder = (uHasDepth > 0.5) ? (1.0 - d) * 0.8 : 0.0;
                    gl_Position = vec4(pos, zOrder, 1.0);
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
