package com.example.videowallpaper

import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws the video onto the wallpaper surface through OpenGL so we can add
 * effects (bloom, RGB split, invert) and tilt parallax.
 *
 * The video decoder writes into videoSurface (handed back through
 * onVideoSurface). If OpenGL fails for any reason, onVideoSurface(null) is
 * called and the wallpaper falls back to plain video with no effects.
 */
class GlRenderer(
    private val target: Surface,
    private val prefs: SharedPreferences,
    private val onVideoSurface: (Surface?) -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("wallpaper-gl").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var hasFrame = false
    private var released = false
    private val texMatrix = FloatArray(16)

    private var aPos = 0
    private var aUv = 0
    private var uTex = 0
    private var uScale = 0
    private var uShift = 0
    private var uInverse = 0
    private var uRgb = 0
    private var uBloom = 0
    private var uVideo = 0

    @Volatile private var surfW = 1
    @Volatile private var surfH = 1
    @Volatile private var vidW = 0
    @Volatile private var vidH = 0
    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f

    private val quad = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
            ))
            position(0)
        }

    fun start() {
        handler.post {
            try {
                init()
            } catch (e: Throwable) {
                e.printStackTrace()
                released = true
                cleanup()
                onVideoSurface(null)
                thread.quitSafely()
            }
        }
    }

    fun setSize(w: Int, h: Int) {
        surfW = w
        surfH = h
        handler.post { render() }
    }

    fun setVideoSize(w: Int, h: Int) {
        vidW = w
        vidH = h
    }

    fun release() {
        handler.post {
            released = true
            cleanup()
            thread.quitSafely()
        }
    }

    private fun init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: throw RuntimeException("No EGL config")

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            display, config, target, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw RuntimeException("eglMakeCurrent failed")
        }

        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uScale = GLES20.glGetUniformLocation(program, "uScale")
        uShift = GLES20.glGetUniformLocation(program, "uShift")
        uInverse = GLES20.glGetUniformLocation(program, "uInverse")
        uRgb = GLES20.glGetUniformLocation(program, "uRgb")
        uBloom = GLES20.glGetUniformLocation(program, "uBloom")
        uVideo = GLES20.glGetUniformLocation(program, "uVideo")

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texId = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener(this, handler)
        surfaceTexture = st
        val vs = Surface(st)
        videoSurface = vs

        onVideoSurface(vs)
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        if (released) return
        val s = surfaceTexture ?: return
        try {
            s.updateTexImage()
            s.getTransformMatrix(texMatrix)
            hasFrame = true
            render()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun render() {
        if (released || program == 0 || !hasFrame) return
        val w = surfW
        val h = surfH
        val vw = vidW
        val vh = vidH
        if (w <= 0 || h <= 0 || vw <= 0 || vh <= 0) return

        // Read the effect settings live.
        val inverse = if (prefs.getBoolean("fx_inverse", false)) 1f else 0f
        val rgb = prefs.getFloat("fx_rgb", 0f) * 0.02f
        val bloom = prefs.getFloat("fx_bloom", 0f)
        val tiltOn = prefs.getBoolean("fx_tilt", false)
        val tiltStrength = prefs.getFloat("fx_tilt_strength", 0.5f)

        // "Cover" scaling: fill the screen, crop the extra.
        val sa = w.toFloat() / h
        val va = vw.toFloat() / vh
        var sx = 1f
        var sy = 1f
        if (va > sa) sx = sa / va else sy = va / sa

        // Leave a little room so tilting can slide the picture.
        val maxShift = if (tiltOn) 0.06f * tiltStrength else 0f
        sx *= (1f - 2f * maxShift)
        sy *= (1f - 2f * maxShift)
        val shiftX = if (tiltOn) tiltX * maxShift else 0f
        val shiftY = if (tiltOn) tiltY * maxShift else 0f

        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uVideo, 0)
        GLES20.glUniformMatrix4fv(uTex, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uScale, sx, sy)
        GLES20.glUniform2f(uShift, shiftX, shiftY)
        GLES20.glUniform1f(uInverse, inverse)
        GLES20.glUniform1f(uRgb, rgb)
        GLES20.glUniform1f(uBloom, bloom)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun cleanup() {
        try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Exception) {}
        try { videoSurface?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        videoSurface = null
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            // Not calling eglTerminate: the display is shared inside the process.
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link failed: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader failed: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aPos;
attribute vec2 aUv;
uniform mat4 uTex;
uniform vec2 uScale;
uniform vec2 uShift;
varying vec2 vUv;
void main() {
    gl_Position = aPos;
    vec2 c = (aUv - 0.5) * uScale + 0.5 + uShift;
    vUv = (uTex * vec4(c, 0.0, 1.0)).xy;
}
"""

        private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform samplerExternalOES uVideo;
uniform float uInverse;
uniform float uRgb;
uniform float uBloom;

vec3 bright(vec2 p) {
    vec3 c = texture2D(uVideo, p).rgb;
    return max(c - vec3(0.55), vec3(0.0));
}

void main() {
    vec3 col;
    if (uRgb > 0.0) {
        col = vec3(
            texture2D(uVideo, vUv + vec2(uRgb, 0.0)).r,
            texture2D(uVideo, vUv).g,
            texture2D(uVideo, vUv - vec2(uRgb, 0.0)).b);
    } else {
        col = texture2D(uVideo, vUv).rgb;
    }

    if (uBloom > 0.0) {
        float r1 = 0.008;
        float r2 = 0.02;
        vec3 g = bright(vUv) * 0.2;
        g += bright(vUv + vec2( r1, 0.0)) * 0.09;
        g += bright(vUv + vec2(-r1, 0.0)) * 0.09;
        g += bright(vUv + vec2(0.0,  r1)) * 0.09;
        g += bright(vUv + vec2(0.0, -r1)) * 0.09;
        g += bright(vUv + vec2( r1,  r1)) * 0.09;
        g += bright(vUv + vec2(-r1,  r1)) * 0.09;
        g += bright(vUv + vec2( r1, -r1)) * 0.09;
        g += bright(vUv + vec2(-r1, -r1)) * 0.09;
        g += bright(vUv + vec2( r2, 0.0)) * 0.07;
        g += bright(vUv + vec2(-r2, 0.0)) * 0.07;
        g += bright(vUv + vec2(0.0,  r2)) * 0.07;
        g += bright(vUv + vec2(0.0, -r2)) * 0.07;
        col += g * uBloom * 2.0;
    }

    col = clamp(col, 0.0, 1.0);
    if (uInverse > 0.5) {
        col = vec3(1.0) - col;
    }
    gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
