package com.asern.reelsaver

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this) // needed to merge best video + audio
            Engine.autoUpdateIfDue(this)     // weekly automatic update
        } catch (e: Exception) {
            Log.e("ReelSaver", "init failed", e)
        }
    }
}
