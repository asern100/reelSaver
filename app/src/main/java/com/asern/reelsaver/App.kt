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
            FFmpeg.getInstance().init(this) // لدمج الصورة والصوت بأعلى جودة
        } catch (e: Exception) {
            Log.e("ReelSaver", "init failed", e)
        }
    }
}
