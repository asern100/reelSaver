package com.asern.reelsaver

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadBtn: Button
    private lateinit var pasteBtn: Button
    private lateinit var updateBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var audioOnly: MaterialSwitch
    private lateinit var playBtn: Button
    private var lastItem: DownloadItem? = null

    private var busy = false
    private val processId = "reel-dl"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadBtn = findViewById(R.id.downloadBtn)
        pasteBtn = findViewById(R.id.pasteBtn)
        updateBtn = findViewById(R.id.updateBtn)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        audioOnly = findViewById(R.id.audioOnly)
        playBtn = findViewById(R.id.playBtn)
        playBtn.setOnClickListener { lastItem?.let { openDownload(it) } }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.historyBtn).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Remember the choice, also for links shared from other apps
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        audioOnly.isChecked = prefs.getBoolean("audio_only", false)
        audioOnly.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean("audio_only", on).apply()
            downloadBtn.text = getString(if (on) R.string.download_audio else R.string.download)
        }
        downloadBtn.text = getString(if (audioOnly.isChecked) R.string.download_audio else R.string.download)

        pasteBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            extractUrl(text)?.let { urlInput.setText(it) } ?: setStatus(getString(R.string.no_link_clipboard))
        }
        downloadBtn.setOnClickListener {
            if (busy) cancel() else startDownload(urlInput.text.toString().trim())
        }
        updateBtn.setOnClickListener { updateEngine() }

        handleShare(intent)

        // Needed for background download progress notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Shared from TikTok/Instagram/Facebook/YouTube: start downloading right away */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()) ?: return
        urlInput.setText(url)
        if (!busy) startDownload(url)
    }

    private fun extractUrl(text: String): String? = Downloader.extractUrl(text)

    private fun startDownload(url: String) {
        if (!url.startsWith("http")) { setStatus(getString(R.string.invalid_link)); return }

        busy = true
        downloadBtn.text = getString(R.string.cancel)
        updateBtn.isEnabled = false
        audioOnly.isEnabled = false
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        playBtn.visibility = View.GONE
        setStatus(getString(R.string.preparing))

        val audio = audioOnly.isChecked
        lifecycleScope.launch {
            try {
                val saved = Downloader.download(this@MainActivity, url, audio, processId) { ev ->
                    runOnUiThread {
                        when (ev) {
                            is Downloader.Event.Status -> {
                                progress.isIndeterminate = true
                                setStatus(ev.text)
                            }
                            is Downloader.Event.Progress -> {
                                progress.isIndeterminate = false
                                progress.progress = ev.percent
                                setStatus(getString(R.string.progress_fmt, ev.percent, ev.etaSec.toInt()))
                            }
                        }
                    }
                }
                lastItem = saved.first()
                setStatus(getString(R.string.done_saved, saved.first().source.label(this@MainActivity), saved.joinToString("\n") { it.title }))
                playBtn.text = getString(if (saved.first().isAudio) R.string.listen else R.string.watch)
                playBtn.visibility = View.VISIBLE
            } catch (e: YoutubeDL.CanceledException) {
                setStatus(getString(R.string.canceled))
            } catch (e: Exception) {
                setStatus(getString(R.string.failed, e.message?.take(400).orEmpty()))
            } finally {
                busy = false
                downloadBtn.text = getString(if (audioOnly.isChecked) R.string.download_audio else R.string.download)
                updateBtn.isEnabled = true
                audioOnly.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun cancel() = Downloader.cancel(processId)

    /** Manual engine update */
    private fun updateEngine() {
        updateBtn.isEnabled = false
        downloadBtn.isEnabled = false
        setStatus(getString(R.string.updating))
        lifecycleScope.launch {
            setStatus(Engine.update(this@MainActivity).message)
            updateBtn.isEnabled = true
            downloadBtn.isEnabled = true
        }
    }

    private fun setStatus(s: String) { status.text = s }
}
