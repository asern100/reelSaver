package com.asern.reelsaver

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast

/**
 * Invisible screen: starts a background job and closes immediately.
 * - From the Share menu ("ReelSaver ⬇ Video" / "🎵 Audio" / "🔇 No music")
 * - From the floating buttons, after "Copy link" (reads the clipboard)
 * "🔇 No music" also accepts a video or audio file shared from the gallery or a file manager.
 */
class QuickDownloadActivity : Activity() {

    companion object {
        const val EXTRA_FROM_CLIPBOARD = "from_clipboard"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_NO_MUSIC = "no_music"
    }

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_SEND) {
            val cls = intent.component?.className.orEmpty()
            val noMusic = cls.endsWith("QuickNoMusic")
            val stream = streamOf(intent)
            if (noMusic && stream != null) removeFromFile(stream)
            else go(Downloader.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)), cls.endsWith("QuickAudio"), noMusic)
        }
        // Clipboard can only be read once this window has focus (Android 10+)
        window.decorView.postDelayed({ if (!handled) finish() }, 3000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled || !intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        go(Downloader.extractUrl(text), intent.getBooleanExtra(EXTRA_AUDIO, false),
            intent.getBooleanExtra(EXTRA_NO_MUSIC, false))
    }

    private fun go(url: String?, audio: Boolean, noMusic: Boolean) {
        handled = true
        if (url == null) {
            Toast.makeText(this, R.string.no_link_clipboard, Toast.LENGTH_LONG).show()
        } else {
            DownloadService.start(this, url, audio && !noMusic, noMusic)
            Toast.makeText(this, when {
                noMusic -> R.string.bg_started_nm
                audio -> R.string.bg_started_audio
                else -> R.string.bg_started_video
            }, Toast.LENGTH_SHORT).show()
        }
        close()
    }

    private fun removeFromFile(uri: Uri) {
        handled = true
        val mime = intent.type?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
            ?: contentResolver.getType(uri).orEmpty()
        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
            Toast.makeText(this, R.string.nm_not_media, Toast.LENGTH_LONG).show()
        } else {
            DownloadService.startLocal(this, uri, displayName(uri), mime, Source.OTHER)
            Toast.makeText(this, R.string.bg_started_nm, Toast.LENGTH_SHORT).show()
        }
        close()
    }

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    private fun displayName(uri: Uri): String {
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment.orEmpty()
        return name.substringBeforeLast('.')
    }

    private fun close() {
        finish()
        overridePendingTransition(0, 0)
    }
}
