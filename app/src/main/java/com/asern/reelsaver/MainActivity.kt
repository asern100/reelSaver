package com.asern.reelsaver

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadBtn: MaterialButton
    private lateinit var playBtn: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var statusCard: View
    private lateinit var modeSeg: Segmented

    private var lastItem: DownloadItem? = null
    private var busy = false
    private val processId = "reel-dl"
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val audioMode get() = modeSeg.selected == 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadBtn = findViewById(R.id.downloadBtn)
        playBtn = findViewById(R.id.playBtn)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        statusCard = findViewById(R.id.statusCard)
        modeSeg = findViewById(R.id.modeSeg)

        // Video / Audio — remembered, also used for links shared from other apps
        modeSeg.setItems(listOf(getString(R.string.mode_video), getString(R.string.mode_audio)))
        modeSeg.selected = if (prefs.getBoolean("audio_only", false)) 1 else 0
        modeSeg.onChange = { i ->
            prefs.edit().putBoolean("audio_only", i == 1).apply()
            refreshButton()
        }
        refreshButton()

        findViewById<View>(R.id.pasteBtn).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            Downloader.extractUrl(text)?.let { urlInput.setText(it) }
                ?: setStatus(getString(R.string.no_link_clipboard))
        }
        downloadBtn.setOnClickListener {
            if (busy) Downloader.cancel(processId) else startDownload(urlInput.text.toString().trim())
        }
        urlInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { downloadBtn.performClick(); true } else false
        }
        playBtn.setOnClickListener { lastItem?.let { openDownload(it) } }
        findViewById<View>(R.id.historyBtn).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Supported platform pills
        val row = findViewById<LinearLayout>(R.id.platforms)
        Source.entries.filter { it != Source.OTHER }.forEach { s ->
            row.addView(TextView(this).apply { showSourceTag(s) },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() })
        }

        handleShare(intent)
        when (intent.getStringExtra("open")) {
            "history" -> startActivity(Intent(this, HistoryActivity::class.java))
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
        }

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
        val url = Downloader.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        urlInput.setText(url)
        if (!busy) startDownload(url)
    }

    private fun refreshButton() {
        downloadBtn.text = getString(when {
            busy -> R.string.cancel
            audioMode -> R.string.download_audio
            else -> R.string.download
        })
        downloadBtn.setIconResource(when {
            busy -> R.drawable.ic_close
            audioMode -> R.drawable.ic_audio
            else -> R.drawable.ic_download
        })
    }

    private fun startDownload(url: String) {
        if (!url.startsWith("http")) { setStatus(getString(R.string.invalid_link)); return }

        busy = true
        refreshButton()
        modeSeg.isEnabled = false
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        playBtn.visibility = View.GONE
        setStatus(getString(R.string.preparing))

        val audio = audioMode
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
                val first = saved.first()
                lastItem = first
                setStatus(getString(R.string.done_saved, first.source.label(this@MainActivity),
                    saved.joinToString("\n") { it.title }))
                playBtn.text = getString(if (first.isAudio) R.string.listen else R.string.watch)
                playBtn.visibility = View.VISIBLE
            } catch (e: YoutubeDL.CanceledException) {
                setStatus(getString(R.string.canceled))
            } catch (e: Exception) {
                setStatus(getString(R.string.failed, e.message?.take(400).orEmpty()))
            } finally {
                busy = false
                refreshButton()
                modeSeg.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private fun setStatus(s: String) {
        status.text = s
        statusCard.visibility = View.VISIBLE
    }
}
