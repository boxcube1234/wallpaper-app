package com.example.videowallpaper

import android.app.KeyguardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.Surface
import android.view.SurfaceHolder
import java.io.File

private const val AUTO = -1

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine() {
        private var player: MediaPlayer? = null
        private var holder: SurfaceHolder? = null
        private val handler = Handler(Looper.getMainLooper())
        private val keyguard by lazy {
            getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        }
        private val sensorManager by lazy {
            getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }

        // OpenGL (effects)
        private var gl: GlRenderer? = null
        private var glSurface: Surface? = null
        private var ready = false

        private var mode = 0                       // 0 = home screen, 1 = lock screen
        private val introSeen = BooleanArray(2)    // has each mode reached its loop part?
        private var introDone: Boolean
            get() = introSeen[mode]
            set(v) { introSeen[mode] = v }

        private var loopStartMs = 0
        private var loopEndMs = 0
        private var pausedByUs = false
        private var resumeMs = AUTO
        private var resumeMode = 0
        private var tick = 0
        private var lastSignature = ""

        private val prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // ---------- tilt (accelerometer) ----------
        private var sensorOn = false
        private val sensorListener = object : SensorEventListener {
            private var fx = 0f
            private var fy = 0f
            private var bx = 0f
            private var by = 0f
            private var init = false

            override fun onSensorChanged(e: SensorEvent) {
                val ax = e.values[0] / 9.81f   // left / right tilt
                val az = e.values[2] / 9.81f   // forward / back tilt
                if (!init) { fx = ax; fy = az; bx = ax; by = az; init = true }
                fx += 0.15f * (ax - fx)        // smooth
                fy += 0.15f * (az - fy)
                bx += 0.004f * (fx - bx)       // slowly re-centre on how you hold the phone
                by += 0.004f * (fy - by)
                gl?.tiltX = ((fx - bx) / 0.3f).coerceIn(-1f, 1f)
                gl?.tiltY = ((fy - by) / 0.3f).coerceIn(-1f, 1f)
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }

        private fun updateSensor() {
            val want = prefs.getBoolean("fx_tilt", false) && !pausedByUs && holder != null
            if (want && !sensorOn) {
                val s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (s != null) {
                    sensorManager.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_GAME)
                    sensorOn = true
                }
            } else if (!want && sensorOn) {
                sensorManager.unregisterListener(sensorListener)
                sensorOn = false
                gl?.tiltX = 0f
                gl?.tiltY = 0f
            }
        }

        // ---------- helpers ----------
        private fun fileFor(m: Int): File {
            val lock = File(filesDir, MainActivity.LOCK_FILE)
            return if (m == 1 && lock.exists()) lock else File(filesDir, MainActivity.VIDEO_FILE)
        }

        private fun wantedMode(): Int =
            if (prefs.getBoolean("lock_enabled", false) && keyguard.isKeyguardLocked) 1 else 0

        // Changes whenever a setting that needs a fresh start changes.
        private fun signature(): String = listOf(
            prefs.getLong("loop_start_ms", 0L), prefs.getLong("loop_end_ms", 0L),
            prefs.getBoolean("lock_enabled", false),
            prefs.getLong("lock_start_ms", 0L), prefs.getLong("lock_end_ms", 0L),
            File(filesDir, MainActivity.VIDEO_FILE).lastModified(),
            File(filesDir, MainActivity.LOCK_FILE).lastModified()
        ).joinToString("|")

        private fun loadRange(dur: Int) {
            val sKey = if (mode == 1) "lock_start_ms" else "loop_start_ms"
            val eKey = if (mode == 1) "lock_end_ms" else "loop_end_ms"
            var start = prefs.getLong(sKey, 0L).toInt()
            var end = prefs.getLong(eKey, 0L).toInt()
            if (end <= 0 || end > dur) end = dur
            if (start < 0 || start >= end) start = 0
            loopStartMs = start
            loopEndMs = end
        }

        // ---------- watchdog ----------
        private val ticker = object : Runnable {
            override fun run() {
                tick++
                if (tick % 10 == 0) checkSettings()
                val p = player
                if (p != null) {
                    try {
                        val pos = p.currentPosition
                        if (pos >= loopStartMs) introDone = true
                        if (p.isPlaying) {
                            if (pos >= loopEndMs) seekToMs(p, loopStartMs)
                        } else if (!pausedByUs) {
                            if (introDone) seekToMs(p, loopStartMs)
                            p.start()
                        }
                    } catch (e: Exception) {
                        restartPlayer()
                    }
                }
                handler.postDelayed(this, if (pausedByUs) 500L else 50L)
            }
        }

        private fun checkSettings() {
            if (holder == null || !ready) return
            val sig = signature()
            if (sig != lastSignature) {
                introSeen.fill(false)
                mode = wantedMode()
                startPlayer(AUTO)
                return
            }
            if (!pausedByUs) {
                val m = wantedMode()
                if (m != mode) switchMode(m)
            }
            updateSensor()
        }

        private fun switchMode(m: Int) {
            val p = player
            val sameFile = p != null && fileFor(m).absolutePath == fileFor(mode).absolutePath
            mode = m
            if (sameFile && p != null) {
                try {
                    loadRange(p.duration)
                    seekToMs(p, if (introDone) loopStartMs else 0)
                    p.start()
                } catch (e: Exception) {
                    startPlayer(AUTO)
                }
            } else {
                startPlayer(AUTO)
            }
        }

        // ---------- engine lifecycle ----------
        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            this.holder = holder
            pausedByUs = false
            ready = false
            mode = wantedMode()
            val at = if (resumeMode == mode) resumeMs else AUTO

            lateinit var renderer: GlRenderer
            renderer = GlRenderer(holder.surface, prefs) { s ->
                handler.post {
                    if (gl !== renderer || this@VideoEngine.holder == null) return@post
                    glSurface = s      // null = OpenGL failed -> plain video, no effects
                    ready = true
                    startPlayer(at)
                }
            }
            gl = renderer
            val f = holder.surfaceFrame
            renderer.setSize(f.width(), f.height())
            renderer.start()

            handler.removeCallbacks(ticker)
            handler.post(ticker)
            updateSensor()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            gl?.setSize(width, height)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            pausedByUs = !visible
            updateSensor()
            val p = player ?: return
            try {
                if (visible) {
                    val m = wantedMode()
                    if (m != mode) { switchMode(m); return }
                    if (prefs.getBoolean("replay_intro", false)) {
                        introDone = false
                        seekToMs(p, 0)
                    }
                    p.start()
                } else {
                    p.pause()
                }
            } catch (e: Exception) {
                restartPlayer()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(ticker)
            try { player?.let { resumeMs = it.currentPosition; resumeMode = mode } } catch (_: Exception) {}
            releasePlayer()
            gl?.release()
            gl = null
            glSurface = null
            ready = false
            this.holder = null
            updateSensor()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(ticker)
            releasePlayer()
            gl?.release()
            gl = null
            this.holder = null
            updateSensor()
            super.onDestroy()
        }

        // ---------- playback ----------
        private fun restartPlayer() = startPlayer(AUTO)

        private fun startPlayer(startAtMs: Int) {
            val h = holder ?: return
            if (!ready) return
            val file = fileFor(mode)
            if (!file.exists()) return
            releasePlayer()
            try {
                val p = MediaPlayer()
                p.setSurface(glSurface ?: h.surface)
                p.setDataSource(file.absolutePath)
                p.isLooping = false
                p.setVolume(0f, 0f) // wallpapers are silent
                p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                p.setOnVideoSizeChangedListener { _, w, ht -> gl?.setVideoSize(w, ht) }
                p.prepare()
                gl?.setVideoSize(p.videoWidth, p.videoHeight)

                loadRange(p.duration)
                lastSignature = signature()

                p.setOnCompletionListener {
                    try {
                        introDone = true
                        seekToMs(it, loopStartMs)
                        it.start()
                    } catch (e: Exception) {
                        handler.post { restartPlayer() }
                    }
                }
                p.setOnErrorListener { _, _, _ ->
                    handler.post { restartPlayer() }
                    true
                }

                val target = when {
                    startAtMs == AUTO -> if (introDone) loopStartMs else 0
                    startAtMs >= loopEndMs -> loopStartMs
                    else -> startAtMs
                }
                if (target > 0) seekToMs(p, target)

                p.start()
                if (pausedByUs) p.pause()
                player = p
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun seekToMs(p: MediaPlayer, ms: Int) {
            if (Build.VERSION.SDK_INT >= 26) {
                p.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                p.seekTo(ms)
            }
        }

        private fun releasePlayer() {
            player?.apply {
                try { setOnCompletionListener(null); setOnErrorListener(null) } catch (_: Exception) {}
                try { stop() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
            player = null
        }
    }
}
