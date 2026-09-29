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

        private val videoFile get() = File(filesDir, MainActivity.VIDEO_FILE)
        private val prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Checks the video position every 40 ms. When it passes the loop END,
        // jump back to the loop START.
        private val ticker = object : Runnable {
            override fun run() {
                val p = player ?: return
                try {
                    if (p.isPlaying && p.currentPosition >= loopEndMs) {
                        seekToMs(p, loopStartMs)
                    }
                } catch (_: Exception) {}
                handler.postDelayed(this, 40)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            this.holder = holder
            startPlayer()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            val p = player ?: return
            try {
                if (visible) {
                    if (prefs.getBoolean("replay_intro", false)) seekToMs(p, 0)
                    p.start()
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                } else {
                    p.pause()
                    handler.removeCallbacks(ticker)
                }
            } catch (_: Exception) {}
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            releasePlayer()
            this.holder = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            releasePlayer()
            super.onDestroy()
        }

        private fun startPlayer() {
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

                // Read the times the user chose, and keep them safe.
                val dur = p.duration
                var start = prefs.getLong("loop_start_ms", 0L).toInt()
                var end = prefs.getLong("loop_end_ms", 0L).toInt()
                if (end <= 0 || end > dur) end = dur
                if (start < 0 || start >= end) start = 0
                loopStartMs = start
                loopEndMs = end

                // If the video reaches its very end, go back to loop start.
                p.setOnCompletionListener {
                    seekToMs(it, loopStartMs)
                    it.start()
                }
                p.setOnErrorListener { _, _, _ -> true }

                p.start() // begins at 0:00 -> this is the "play once" part
                player = p
                handler.post(ticker)
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
            handler.removeCallbacks(ticker)
            player?.apply {
                try { stop() } catch (_: Exception) {}
                release()
            }
            player = null
        }
    }
}
