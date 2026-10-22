package com.asern.reelsaver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Shared download core used by the main screen and the background service */
object Downloader {

    sealed class Event {
        data class Status(val text: String) : Event()
        data class Progress(val percent: Int, val etaSec: Long) : Event()
    }

    fun extractUrl(text: String?): String? =
        text?.let { Regex("""https?://\S+""").find(it)?.value }

    /**
     * Downloads [url] (video or MP3), saves to Download/ReelSaver, records it in history.
     * Updates the engine and retries once if the first attempt fails.
     * [onEvent] is called from a background thread.
     */
    suspend fun download(
        c: Context, url: String, audio: Boolean, processId: String,
        onEvent: (Event) -> Unit,
    ): List<DownloadItem> {
        val tmp = File(c.cacheDir, "dl-$processId").apply { deleteRecursively(); mkdirs() }
        try {
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
            if (Engine.lock.isLocked) onEvent(Event.Status(c.getString(R.string.waiting_update)))
            try {
                run(request, processId, onEvent)
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: Exception) {
                // Platforms change often: update the engine and retry once
                onEvent(Event.Status(c.getString(R.string.retry_updating)))
                val up = Engine.update(c)
                if (!up.updated) throw e
                tmp.listFiles()?.forEach { it.delete() }
                onEvent(Event.Status(up.message + "\n" + c.getString(R.string.retrying)))
                run(request, processId, onEvent)
            }
            val files = tmp.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
            if (files.isEmpty()) throw Exception(c.getString(R.string.no_file))
            val source = Source.fromUrl(url)
            val saved = withContext(Dispatchers.IO) { files.map { save(c, it, source) } }
            saved.reversed().forEach { HistoryStore.add(c, it) }
            return saved
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun cancel(processId: String) = YoutubeDL.getInstance().destroyProcessById(processId)

    private suspend fun run(request: YoutubeDLRequest, processId: String, onEvent: (Event) -> Unit) =
        Engine.lock.withLock {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId, false) { p, eta, _ ->
                    if (p > 0) onEvent(Event.Progress(p.toInt(), eta))
                }
            }
        }

    /** Copies the file to Download/ReelSaver so it shows in the gallery and file manager */
    private fun save(c: Context, src: File, source: Source): DownloadItem {
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
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception(c.getString(R.string.cant_write))
            c.contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            c.contentResolver.update(uri, values, null, null)
            savedUri = uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ReelSaver")
            dir.mkdirs()
            savedUri = Uri.fromFile(src.copyTo(File(dir, src.name), overwrite = true))
        }
        return DownloadItem(src.nameWithoutExtension, savedUri.toString(), mime, source, System.currentTimeMillis())
    }
}
