package com.asern.reelsaver

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(UiPrefs.nightMode(this))
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this) // needed to merge best video + audio
            Engine.autoUpdateIfDue(this)     // weekly automatic update
        } catch (e: Exception) {
            Log.e("ReelSaver", "init failed", e)
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
