package com.asern.reelsaver

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Download engine (yt-dlp) updates: manual, weekly automatic, and on download failure */
object Engine {
    private const val PREFS = "engine"
    private const val KEY_LAST = "last_update"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    /** Downloads and updates never run at the same time (an update replaces the engine file) */
    val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Result(val updated: Boolean, val message: String)

    /** Updates now and returns a localized message. */
    suspend fun update(c: Context): Result = lock.withLock {
        App.ready.await()
        try {
            val st = YoutubeDL.getInstance()
                .updateYoutubeDL(c.applicationContext, YoutubeDL.UpdateChannel._STABLE)
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST, System.currentTimeMillis()).apply()
            val v = version(c)
            if (st == YoutubeDL.UpdateStatus.DONE) Result(true, c.getString(R.string.engine_updated, v))
            else Result(false, c.getString(R.string.engine_latest, v))
        } catch (e: Exception) {
            Log.e("ReelSaver", "update failed", e)
            Result(false, c.getString(R.string.engine_failed))
        }
    }

    /** Called on app start: if auto-update is on and a week has passed, update in the background */
    fun autoUpdateIfDue(c: Context) {
        if (!isAutoUpdate(c)) return
        val last = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0)
        if (System.currentTimeMillis() - last < WEEK) return
        scope.launch { update(c) }
    }

    fun version(c: Context): String =
        YoutubeDL.getInstance().versionName(c.applicationContext)
            ?: YoutubeDL.getInstance().version(c.applicationContext) ?: "?"

    fun isAutoUpdate(c: Context) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", true)

    fun setAutoUpdate(c: Context, on: Boolean) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", on).apply()

    fun lastUpdate(c: Context): Long =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0)
}
