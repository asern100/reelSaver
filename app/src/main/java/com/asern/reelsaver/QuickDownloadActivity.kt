package com.asern.reelsaver

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible screen: starts a background download and closes immediately.
 * - From the Share menu ("ReelSaver ⬇ Video" / "ReelSaver 🎵 Audio")
 * - From the floating buttons, after "Copy link" (reads the clipboard)
 */
class QuickDownloadActivity : Activity() {

    companion object {
        const val EXTRA_FROM_CLIPBOARD = "from_clipboard"
        const val EXTRA_AUDIO = "audio"
    }

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_SEND) {
            val audio = intent.component?.className?.endsWith("QuickAudio") == true
            go(Downloader.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)), audio)
        }
        // Clipboard can only be read once this window has focus (Android 10+)
        window.decorView.postDelayed({ if (!handled) finish() }, 3000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled || !intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        go(Downloader.extractUrl(text), intent.getBooleanExtra(EXTRA_AUDIO, false))
    }

    private fun go(url: String?, audio: Boolean) {
        handled = true
        if (url == null) {
            Toast.makeText(this, R.string.no_link_clipboard, Toast.LENGTH_LONG).show()
        } else {
            DownloadService.start(this, url, audio)
            Toast.makeText(this, if (audio) R.string.bg_started_audio else R.string.bg_started_video,
                Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
