package com.asern.reelsaver

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {

    companion object {
        /** Completes when the download engine is unpacked and ready (first launch takes a few seconds) */
        val ready = CompletableDeferred<Unit>()
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(UiPrefs.nightMode(this))
        // Unpack the engine in the background so the UI opens instantly
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@App)
                FFmpeg.getInstance().init(this@App) // needed to merge best video + audio
            } catch (e: Exception) {
                Log.e("ReelSaver", "init failed", e)
            }
            ready.complete(Unit)
            Engine.autoUpdateIfDue(this@App) // weekly automatic update
        }
    }
}

/** Theme choice: Automatic (follow phone) / Light / Dark */
object UiPrefs {
    private fun p(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun nightMode(c: Context) = p(c).getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setNightMode(c: Context, mode: Int) {
        p(c).edit().putInt("night", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
