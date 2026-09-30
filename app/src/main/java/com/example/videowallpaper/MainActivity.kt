package com.example.videowallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val VIDEO_FILE = "video.mp4"
        private const val REQ_VIDEO = 1
    }

    private lateinit var status: TextView
    private lateinit var startBox: EditText
    private lateinit var endBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply { text = "Video Wallpaper"; textSize = 26f })

        status = TextView(this).apply { setPadding(0, pad, 0, pad) }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "1. Pick a video"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "video/*"
                    }, REQ_VIDEO
                )
            }
        })

        root.addView(TextView(this).apply {
            text = "\n2. Choose the loop part (in seconds).\n" +
                "Everything BEFORE the loop start plays once (the intro). " +
                "Then the loop part repeats forever."
        })

        startBox = EditText(this).apply {
            hint = "Loop starts at (seconds), e.g. 5"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            val s = prefs.getLong("loop_start_ms", 0L)
            if (s > 0) setText((s / 1000.0).toString())
        }
        endBox = EditText(this).apply {
            hint = "Loop ends at (seconds), empty = end of video"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            val e = prefs.getLong("loop_end_ms", 0L)
            if (e > 0) setText((e / 1000.0).toString())
        }
        root.addView(startBox)
        root.addView(endBox)

        root.addView(Switch(this).apply {
            text = "Replay intro whenever I return to the home screen"
            isChecked = prefs.getBoolean("replay_intro", false)
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("replay_intro", on).apply()
            }
        })

        root.addView(Button(this).apply {
            text = "3. Save and set as live wallpaper"
            setOnClickListener { if (saveTimes()) setWallpaper() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_VIDEO || resultCode != RESULT_OK) return
        toast("Copying video...")
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    File(filesDir, VIDEO_FILE).outputStream().use { input.copyTo(it) }
                }
                runOnUiThread { refreshStatus(); toast("Video saved") }
            } catch (e: Exception) {
                runOnUiThread { toast("Failed: ${e.message}") }
            }
        }.start()
    }

    private fun durationMs(): Long {
        val f = File(filesDir, VIDEO_FILE)
        if (!f.exists()) return 0
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: Exception) { 0 } finally { r.release() }
    }

    private fun saveTimes(): Boolean {
        val dur = durationMs()
        if (dur == 0L) { toast("Pick a video first"); return false }

        val startSec = startBox.text.toString().toDoubleOrNull() ?: 0.0
        val endSec = endBox.text.toString().toDoubleOrNull() ?: 0.0
        val startMs = (startSec * 1000).toLong()
        val endMs = (endSec * 1000).toLong()

        if (startMs >= dur) { toast("Loop start must be before the end of the video"); return false }
        if (endMs != 0L && endMs <= startMs) { toast("Loop end must be after loop start"); return false }
        if (endMs > dur) { toast("Loop end is longer than the video"); return false }

        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putLong("loop_start_ms", startMs)
            .putLong("loop_end_ms", endMs)
            .apply()
        return true
    }

    private fun setWallpaper() {
        startActivity(
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, VideoWallpaperService::class.java)
            )
        )
    }

    private fun refreshStatus() {
        val dur = durationMs()
        status.text = if (dur == 0L) "No video selected yet"
        else "Video selected. Length: ${"%.1f".format(dur / 1000.0)} seconds"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
