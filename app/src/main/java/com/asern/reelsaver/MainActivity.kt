package com.asern.reelsaver

import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
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

        // نتفكّر الاختيار حتى للمشاركة من التطبيقات الأخرى
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
            extractUrl(text)?.let { urlInput.setText(it) } ?: setStatus("ما لقيتش رابط في الحافظة")
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

    /** لما تعمل "مشاركة" للريل من التطبيق ← يبدا التحميل وحدو */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val url = extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()) ?: return
        urlInput.setText(url)
        if (!busy) startDownload(url)
    }

    private fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value

    private fun startDownload(url: String) {
        if (!url.startsWith("http")) { setStatus("حط رابط صحيح"); return }

        busy = true
        downloadBtn.text = getString(R.string.cancel)
        updateBtn.isEnabled = false
        audioOnly.isEnabled = false
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        setStatus("نجهّز في التحميل…")

        lifecycleScope.launch {
            val tmp = File(cacheDir, "dl").apply { deleteRecursively(); mkdirs() }
            try {
                val audio = audioOnly.isChecked
                val request = YoutubeDLRequest(url).apply {
                    if (audio) {
                        // الصوت فقط، أعلى جودة، محوّل لـ MP3
                        addOption("-f", "ba/b")
                        addOption("-x")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "0")
                    } else {
                        // أفضل فيديو + أفضل صوت، وإلا أفضل ملف جاهز
                        addOption("-f", "bv*+ba/b")
                        addOption("--merge-output-format", "mp4")
                    }
                    addOption("--no-mtime")
                    addOption("-o", tmp.absolutePath + "/%(extractor)s_%(id)s.%(ext)s")
                }
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, processId, false) { p, eta, _ ->
                        runOnUiThread {
                            if (p > 0) {
                                progress.isIndeterminate = false
                                progress.progress = p.toInt()
                                setStatus("جاري التحميل… ${p.toInt()}%  (باقي ${eta}s)")
                            }
                        }
                    }
                }
                val files = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
                if (files.isEmpty()) throw Exception("ما تحمّل حتى ملف")
                val saved = withContext(Dispatchers.IO) { files.map { saveToDownloads(it) } }
                setStatus("✅ تحمّل في Download/ReelSaver:\n" + saved.joinToString("\n"))
            } catch (e: YoutubeDL.CanceledException) {
                setStatus("تلغى التحميل")
            } catch (e: Exception) {
                setStatus("❌ فشل التحميل:\n${e.message?.take(400)}\n\nجرّب «تحديث المحرك» وعاود. بعض الريلز الخاصة (private) ما تتحمّلش.")
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

    private fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(processId)
    }

    /** ينسخ الملف لـ Download/ReelSaver باش يبان في الغاليري ومدير الملفات */
    private fun saveToDownloads(src: File): String {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ReelSaver")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("ما نجمش نكتب في Downloads")
            contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ReelSaver")
            dir.mkdirs()
            src.copyTo(File(dir, src.name), overwrite = true)
        }
        return src.name
    }

    /** تيك توك وانستغرام يبدّلو كل مرة، فالتحديث يصلّح أغلب المشاكل */
    private fun updateEngine() {
        updateBtn.isEnabled = false
        downloadBtn.isEnabled = false
        setStatus("نحدّث في محرك التحميل (yt-dlp)…")
        lifecycleScope.launch {
            val msg = try {
                val st = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._STABLE)
                }
                val v = YoutubeDL.getInstance().versionName(applicationContext)
                "✅ المحرك: ${st?.name ?: "OK"} — النسخة $v"
            } catch (e: Exception) {
                "❌ فشل التحديث: ${e.message}"
            }
            setStatus(msg)
            updateBtn.isEnabled = true
            downloadBtn.isEnabled = true
        }
    }

    private fun setStatus(s: String) { status.text = s }
}
