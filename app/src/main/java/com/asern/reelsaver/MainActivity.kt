package com.asern.reelsaver

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    private fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value

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

        lifecycleScope.launch {
            val tmp = File(cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
            try {
                val audio = audioOnly.isChecked
                val request = YoutubeDLRequest(url).apply {
                    if (audio) {
                        // Audio only, best quality, converted to MP3
                        addOption("-f", "ba/b")
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    } else {
                        // Best video + best audio, otherwise best single file
                        addOption("-f", "bv*+ba/b")
                        // Highest resolution first; at equal resolution prefer H.264 so every phone can play it
                        addOption("-S", "res,fps,vcodec:h264,acodec:m4a")
                        addOption("--merge-output-format", "mp4")
                    }
                    addOption("--no-mtime")
                    addOption("--no-playlist")
                    addOption("-o", tmp.absolutePath + "/%(title).70B [%(id)s].%(ext)s")
                }
                if (Engine.lock.isLocked) setStatus(getString(R.string.waiting_update))
                try {
                    runRequest(request)
                } catch (e: YoutubeDL.CanceledException) {
                    throw e
                } catch (e: Exception) {
                    // Platforms change often: update the engine and retry once
                    setStatus(getString(R.string.retry_updating))
                    progress.isIndeterminate = true
                    val up = Engine.update(this@MainActivity)
                    if (!up.updated) throw e
                    tmp.listFiles()?.forEach { it.delete() }
                    setStatus(up.message + "\n" + getString(R.string.retrying))
                    runRequest(request)
                }
                val files = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
                if (files.isEmpty()) throw Exception(getString(R.string.no_file))
                val source = Source.fromUrl(url)
                val saved = withContext(Dispatchers.IO) { files.map { saveToDownloads(it, source) } }
                saved.reversed().forEach { HistoryStore.add(this@MainActivity, it) }
                lastItem = saved.first()
                setStatus(getString(R.string.done_saved, source.label(this@MainActivity), saved.joinToString("\n") { it.title }))
                playBtn.text = getString(if (saved.first().isAudio) R.string.listen else R.string.watch)
                playBtn.visibility = View.VISIBLE
            } catch (e: YoutubeDL.CanceledException) {
                setStatus(getString(R.string.canceled))
            } catch (e: Exception) {
                setStatus(getString(R.string.failed, e.message?.take(400).orEmpty()))
            } finally {
                tmp.deleteRecursively()
                busy = false
                downloadBtn.text = getString(if (audioOnly.isChecked) R.string.download_audio else R.string.download)
                updateBtn.isEnabled = true
                audioOnly.isEnabled = true
                progress.visibility = View.GONE
            }
        }
    }

    private suspend fun runRequest(request: YoutubeDLRequest) = Engine.lock.withLock {
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().execute(request, processId, false) { p, eta, _ ->
                runOnUiThread {
                    if (p > 0) {
                        progress.isIndeterminate = false
                        progress.progress = p.toInt()
                        setStatus(getString(R.string.progress_fmt, p.toInt(), eta.toInt()))
                    }
                }
            }
        }
    }

    private fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    /** Copies the file to Download/ReelSaver so it shows in the gallery and file manager */
    private fun saveToDownloads(src: File, source: Source): DownloadItem {
        val mime = when (src.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus", "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
        val savedUri: Uri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReelSaver")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception(getString(R.string.cant_write))
            contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            savedUri = uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ReelSaver")
            dir.mkdirs()
            savedUri = Uri.fromFile(src.copyTo(File(dir, src.name), overwrite = true))
        }
        return DownloadItem(src.nameWithoutExtension, savedUri.toString(), mime, source, System.currentTimeMillis())
    }

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
