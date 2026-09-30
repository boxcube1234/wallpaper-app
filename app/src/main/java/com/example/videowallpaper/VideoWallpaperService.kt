package com.example.videowallpaper

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine() {
        private var player: MediaPlayer? = null
        private var holder: SurfaceHolder? = null
        private val handler = Handler(Looper.getMainLooper())

        private var loopStartMs = 0
        private var loopEndMs = 0
        private var introDone = false   // true once we have reached the loop part
        private var pausedByUs = false  // true only when WE paused it (screen hidden)
        private var resumeMs = 0        // where to carry on after Android rebuilds the surface

        private val videoFile get() = File(filesDir, MainActivity.VIDEO_FILE)
        private val prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Watchdog: runs all the time. While the wallpaper is visible it
        //  1) jumps back to the loop start when the loop end is reached
        //  2) restarts playback if the video stopped for any reason
        private val ticker = object : Runnable {
            override fun run() {
                val p = player
                if (p != null) {
                    try {
                        val pos = p.currentPosition
                        if (pos >= loopStartMs) introDone = true
                        if (p.isPlaying) {
                            if (pos >= loopEndMs) seekToMs(p, loopStartMs)
                        } else if (!pausedByUs) {
                            // stopped by itself (stall / finished) -> resume in the loop
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

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            this.holder = holder
            pausedByUs = false
            startPlayer(resumeMs)
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            val p = player ?: return
            try {
                if (visible) {
                    pausedByUs = false
                    if (prefs.getBoolean("replay_intro", false)) {
                        introDone = false
                        seekToMs(p, 0)
                    }
                    p.start()
                } else {
                    pausedByUs = true
                    p.pause()
                }
            } catch (e: Exception) {
                restartPlayer()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            handler.removeCallbacks(ticker)
            try { player?.let { resumeMs = it.currentPosition } } catch (_: Exception) {}
            releasePlayer()
            this.holder = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(ticker)
            releasePlayer()
            super.onDestroy()
        }

        private fun restartPlayer() {
            startPlayer(if (introDone) loopStartMs else 0)
        }

        private fun startPlayer(startAtMs: Int) {
            val h = holder ?: return
            if (!videoFile.exists()) return
            releasePlayer()
            try {
                val p = MediaPlayer()
                p.setSurface(h.surface)
                p.setDataSource(videoFile.absolutePath)
                p.isLooping = false
                p.setVolume(0f, 0f) // silent
                p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                p.prepare()

                val dur = p.duration
                var start = prefs.getLong("loop_start_ms", 0L).toInt()
                var end = prefs.getLong("loop_end_ms", 0L).toInt()
                if (end <= 0 || end > dur) end = dur
                if (start < 0 || start >= end) start = 0
                loopStartMs = start
                loopEndMs = end

                // Video reached its very end -> go back to the loop start
                p.setOnCompletionListener {
                    try {
                        introDone = true
                        seekToMs(it, loopStartMs)
                        it.start()
                    } catch (e: Exception) {
                        handler.post { restartPlayer() }
                    }
                }
                // Any error -> rebuild the player instead of dying silently
                p.setOnErrorListener { _, _, _ ->
                    handler.post { restartPlayer() }
                    true
                }

                val target = if (startAtMs >= end) start else startAtMs
                if (target > 0) seekToMs(p, target)
                p.start()
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
