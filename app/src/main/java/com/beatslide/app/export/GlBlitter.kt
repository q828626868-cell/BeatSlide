package com.beatslide.app.export

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 匯出用的 GPU 管線：
 *   SlideRenderer 用硬體 Canvas 畫到 [canvasSurface]（背後是 SurfaceTexture）
 *   → 以 OpenGL 貼到編碼器的輸入 Surface，並用 eglPresentationTimeANDROID 設定精確的時間戳記。
 * 這樣影片時間完全由「第幾格」決定，不受實際算圖速度影響。
 *
 * 必須在同一條執行緒上建立、使用、釋放。
 */
class GlBlitter(encoderSurface: Surface, private val width: Int, private val height: Int) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val texId: Int
    private val program: Int
    private val surfaceTexture: SurfaceTexture
    /** 給 Canvas 畫的 Surface */
    val canvasSurface: Surface

    private val frameThread = HandlerThread("BeatSlideFrames").apply { start() }
    private val frameLock = Object()
    private var frameAvailable = false
    private val texMatrix = FloatArray(16)

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "EGL 初始化失敗" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "找不到可用的 EGL 設定" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "無法建立 EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "無法建立編碼器 Surface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent 失敗" }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = buildProgram(VS.trimIndent(), FS.trimIndent())

        surfaceTexture = SurfaceTexture(texId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener({
                synchronized(frameLock) { frameAvailable = true; frameLock.notifyAll() }
            }, Handler(frameThread.looper))
        }
        canvasSurface = Surface(surfaceTexture)
    }

    /** 等 Canvas 那格畫好，貼到編碼器 Surface，並送出（時間戳記單位：奈秒） */
    fun publishFrame(ptsNs: Long) {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + 3000
            while (!frameAvailable) {
                val left = deadline - System.currentTimeMillis()
                check(left > 0) { "等待畫面逾時" }
                frameLock.wait(left)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexM"), 1, false, texMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)

        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        runCatching { canvasSurface.release() }
        runCatching { surfaceTexture.release() }
        frameThread.quitSafely()
        if (display != EGL14.EGL_NO_DISPLAY) {
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader 編譯失敗：" + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader 連結失敗：" + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val VS = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTexM;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTexM * aTex).xy; }
        """
        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """
    }
}
