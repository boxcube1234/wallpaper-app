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
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    companion object {
        const val VIDEO_FILE = "video.mp4"
        const val LOCK_FILE = "lock_video.mp4"
        private const val REQ_VIDEO = 1
        private const val REQ_LOCK = 2
    }

    private lateinit var status: TextView
    private lateinit var startBox: EditText
    private lateinit var endBox: EditText
    private lateinit var lockStartBox: EditText
    private lateinit var lockEndBox: EditText
    private val prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun tv(t: String, size: Float = 14f, top: Int = 0) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, top, 0, 0)
        }
        fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }
        fun box(h: String, key: String) = EditText(this).apply {
            hint = h
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            val v = prefs.getLong(key, 0L)
            if (v > 0) setText((v / 1000.0).toString())
        }
        fun sw(t: String, key: String) = Switch(this).apply {
            text = t
            isChecked = prefs.getBoolean(key, false)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
        fun slider(name: String, key: String, def: Float) {
            val v = prefs.getFloat(key, def)
            val label = tv("$name: ${(v * 100).toInt()}%", 14f, pad / 2)
            root.addView(label)
            root.addView(SeekBar(this).apply {
                max = 100
                progress = (v * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        label.text = "$name: $p%"
                        prefs.edit().putFloat(key, p / 100f).apply()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }

        root.addView(tv("Video Wallpaper", 26f))
        status = tv("", 14f, pad / 2)
        root.addView(status)

        // ---- Home screen ----
        root.addView(tv("HOME SCREEN", 16f, pad))
        root.addView(btn("Pick a video") { pickVideo(REQ_VIDEO) })
        root.addView(tv("Everything BEFORE the loop start plays once. Then the loop part repeats."))
        startBox = box("Loop starts at (seconds), e.g. 5", "loop_start_ms")
        endBox = box("Loop ends at (seconds), empty = end of video", "loop_end_ms")
        root.addView(startBox)
        root.addView(endBox)

        // ---- Lock screen ----
        root.addView(tv("LOCK SCREEN", 16f, pad))
        root.addView(sw("Use a different loop on the lock screen", "lock_enabled"))
        lockStartBox = box("Lock loop starts at (seconds)", "lock_start_ms")
        lockEndBox = box("Lock loop ends at (seconds), empty = end", "lock_end_ms")
        root.addView(lockStartBox)
        root.addView(lockEndBox)
        root.addView(btn("Pick a separate lock screen video (optional)") { pickVideo(REQ_LOCK) })
        root.addView(btn("Use the same video on lock screen") {
            File(filesDir, LOCK_FILE).delete()
            refreshStatus()
            toast("Lock screen now uses the main video")
        })

        // ---- Effects (update live) ----
        root.addView(tv("EFFECTS (0% = off)", 16f, pad))
        slider("Bloom (glow)", "fx_bloom", 0f)
        slider("RGB split", "fx_rgb", 0f)
        root.addView(sw("Inverse colors", "fx_inverse"))
        root.addView(sw("Tilt parallax (move the phone)", "fx_tilt"))
        slider("Tilt strength", "fx_tilt_strength", 0.5f)

        // ---- Other ----
        root.addView(sw("Replay intro whenever I return to the home screen", "replay_intro"))

        root.addView(btn("Save changes") {
            if (saveTimes()) toast("Saved. The wallpaper updates in a moment.")
        })
        root.addView(btn("Set as live wallpaper") {
            if (saveTimes()) setWallpaper()
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun pickVideo(code: Int) {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }, code
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val name = when (requestCode) {
            REQ_VIDEO -> VIDEO_FILE
            REQ_LOCK -> LOCK_FILE
            else -> return
        }
        toast("Copying video...")
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    File(filesDir, name).outputStream().use { input.copyTo(it) }
                }
                runOnUiThread { refreshStatus(); toast("Video saved") }
            } catch (e: Exception) {
                runOnUiThread { toast("Failed: ${e.message}") }
            }
        }.start()
    }

    private fun durationMs(f: File): Long {
        if (!f.exists()) return 0
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (e: Exception) { 0 } finally { r.release() }
    }

    private fun parseMs(b: EditText): Long =
        ((b.text.toString().toDoubleOrNull() ?: 0.0) * 1000).toLong()

    private fun validRange(s: Long, e: Long, dur: Long, name: String): Boolean {
        if (s >= dur) { toast("$name start must be before the end of the video"); return false }
        if (e != 0L && e <= s) { toast("$name end must be after its start"); return false }
        if (e > dur) { toast("$name end is longer than the video"); return false }
        return true
    }

    private fun saveTimes(): Boolean {
        val dur = durationMs(File(filesDir, VIDEO_FILE))
        if (dur == 0L) { toast("Pick a video first"); return false }

        val s = parseMs(startBox); val e = parseMs(endBox)
        if (!validRange(s, e, dur, "Home loop")) return false

        val lockFile = File(filesDir, LOCK_FILE)
        val lockDur = if (lockFile.exists()) durationMs(lockFile) else dur
        val ls = parseMs(lockStartBox); val le = parseMs(lockEndBox)
        if (!validRange(ls, le, lockDur, "Lock screen loop")) return false

        prefs.edit()
            .putLong("loop_start_ms", s).putLong("loop_end_ms", e)
            .putLong("lock_start_ms", ls).putLong("lock_end_ms", le)
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
        val dur = durationMs(File(filesDir, VIDEO_FILE))
        val lockFile = File(filesDir, LOCK_FILE)
        val main = if (dur == 0L) "No video selected yet"
        else "Video: ${"%.1f".format(dur / 1000.0)} seconds"
        val lock = if (lockFile.exists())
            "Lock screen video: ${"%.1f".format(durationMs(lockFile) / 1000.0)} seconds"
        else "Lock screen video: same as main"
        status.text = "$main\n$lock"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
