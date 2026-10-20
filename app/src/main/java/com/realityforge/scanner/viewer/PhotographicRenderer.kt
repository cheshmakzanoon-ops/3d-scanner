package com.realityforge.scanner.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Unlit photographic inspection: no invented lighting, texture flips, or geometry edits. */
class PhotographicRenderer(
    private val asset: PhotographicAsset,
    private val reportFailure: (String) -> Unit = {},
) : GLSurfaceView.Renderer, RenderControls {
    @Volatile var failure: String? = null
        private set
    @Volatile var framesDrawn = 0
        private set
    private var yaw = 0f
    private var pitch = 0f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var width = 1
    private var height = 1
    private var estimatedModelGpuBytes = 0L
    private var program = 0
    private var vao = 0
    private var texture = 0
    private val buffers = IntArray(2)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Call only on the GL thread (GLSurfaceView.queueEvent in the UI). */
    override fun orbit(horizontalDegrees: Float, verticalDegrees: Float) {
        val horizontal = horizontalDegrees; val vertical = verticalDegrees
        if (!horizontal.isFinite() || !vertical.isFinite()) return
        yaw = (yaw + horizontal).rem(360f)
        pitch = (pitch + vertical).coerceIn(-89f, 89f)
    }
    override fun magnify(factor: Float) {
        if (factor.isFinite() && factor > 0f) zoom = (zoom * factor).coerceIn(.35f, 5f)
    }
    override fun pan(horizontal: Float, vertical: Float) {
        if (!horizontal.isFinite() || !vertical.isFinite()) return
        panX = (panX + horizontal).coerceIn(-2f, 2f)
        panY = (panY + vertical).coerceIn(-2f, 2f)
    }
    override fun reset() { yaw = 0f; pitch = 0f; zoom = 1f; panX = 0f; panY = 0f }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // EGL context loss invalidates old names. Never delete old names in a NEW context.
        program = 0; vao = 0; texture = 0; buffers.fill(0); failure = null
        try {
            program = link(VERTEX_SHADER, FRAGMENT_SHADER)
            val names = IntArray(1)
            GLES30.glGenVertexArrays(1, names, 0); vao = names[0]
            GLES30.glBindVertexArray(vao)
            GLES30.glGenBuffers(2, buffers, 0)
            val count = asset.positions.size / 3
            estimatedModelGpuBytes = RenderBudget().admit(28L + asset.png.size,count.toLong(),asset.indices.size.toLong(),asset.imageWidth,asset.imageHeight).estimatedGpuBytes
            val vertices = ByteBuffer.allocateDirect(count * 20).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until count) {
                for (j in 0..2) vertices.put((asset.positions[3*i+j] - asset.center[j]) / asset.radius)
                vertices.put(asset.uv[2*i]); vertices.put(asset.uv[2*i+1])
            }
            vertices.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, count * 20, vertices, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
            val indices = ByteBuffer.allocateDirect(asset.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            indices.put(asset.indices).position(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, asset.indices.size * 4, indices, GLES30.GL_STATIC_DRAW)
            val maxTexture = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            require(asset.imageWidth <= maxTexture[0] && asset.imageHeight <= maxTexture[0]) { "Atlas exceeds this GPU's texture limit." }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inPremultiplied = false
            }
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(asset.png, 0, asset.png.size, options)) { "Cannot decode embedded PNG." }
            try {
                require(bitmap.width == asset.imageWidth && bitmap.height == asset.imageHeight)
                GLES30.glGenTextures(1, names, 0); texture = names[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                // Upload rows unchanged. glTF v=0 samples the PNG's FIRST (top) row.
                // Sampling/output remain encoded sRGB for this explicitly UNLIT diagnostic.
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            } finally { bitmap.recycle() }
            GLES30.glBindVertexArray(0)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU upload failed." }
        } catch (e: Exception) { failed(e) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
        try {
            RenderBudget().admitViewport(estimatedModelGpuBytes,this.width,this.height)
            GLES30.glViewport(0,0,this.width,this.height)
        } catch (e: Exception) { failed(e) }
    }
    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(.055f, .065f, .075f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (program == 0 || failure != null) return
        try {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUseProgram(program)
            // Frame the full bounding sphere in either portrait or landscape.
            val aspect = width.toFloat() / height
            val distance = 3.0f / minOf(1f, aspect) / zoom
            Matrix.perspectiveM(projection, 0, 45f, aspect, .02f, 100f)
            Matrix.setLookAtM(view, 0, panX, panY, distance, panX, panY, 0f, 0f, 1f, 0f)
            Matrix.setIdentityM(model, 0)
            Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
            Matrix.multiplyMM(mv, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uCutoff"), asset.alphaCutoff)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uAtlas"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, asset.indices.size, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glBindVertexArray(0)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU draw failed." }
            framesDrawn++
        } catch (e: Exception) { failed(e) }
    }
    private fun failed(e: Exception) {
        failure = e.message ?: "Photographic renderer failed."
        releaseGlResources()
        reportFailure(requireNotNull(failure))
    }
    /** Explicit deletion while this renderer's context is current; safe to call repeatedly. */
    fun releaseGlResources() {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(2, buffers, 0)
        if (program != 0) GLES30.glDeleteProgram(program)
        texture = 0; vao = 0; program = 0; buffers.fill(0)
    }
    private fun shader(type: Int, source: String): Int {
        val id = GLES30.glCreateShader(type)
        check(id != 0) { "Cannot allocate GPU shader." }
        GLES30.glShaderSource(id, source); GLES30.glCompileShader(id)
        val ok = IntArray(1); GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(id); GLES30.glDeleteShader(id); error(log) }
        return id
    }
    private fun link(vertex: String, fragment: String): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertex)
        var fs = 0; var id = 0
        try {
            fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
            id = GLES30.glCreateProgram(); check(id != 0)
            GLES30.glAttachShader(id, vs); GLES30.glAttachShader(id, fs); GLES30.glLinkProgram(id)
            val ok = IntArray(1); GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { GLES30.glGetProgramInfoLog(id) }
            return id
        } catch (e: Exception) { if (id != 0) GLES30.glDeleteProgram(id); throw e }
        finally { GLES30.glDeleteShader(vs); if (fs != 0) GLES30.glDeleteShader(fs) }
    }
    companion object {
        private val VERTEX_SHADER = """
            #version 300 es
            layout(location=0) in vec3 aPosition;
            layout(location=1) in vec2 aUv;
            uniform mat4 uMvp;
            out vec2 vUv;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); vUv = aUv; }
        """.trimIndent()
        private val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            uniform sampler2D uAtlas;
            uniform float uCutoff;
            in vec2 vUv;
            out vec4 result;
            void main() {
                vec4 color = texture(uAtlas, vUv);
                if (color.a < uCutoff) discard;
                result = vec4(color.rgb, 1.0);
            }
        """.trimIndent()
    }
}
