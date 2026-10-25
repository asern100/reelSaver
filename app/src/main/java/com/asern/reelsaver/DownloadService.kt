package com.asern.reelsaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Downloads in the background with a progress notification — the app never opens */
class DownloadService : Service() {

    companion object {
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val FG_ID = 1
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO = "audio"
        private const val EXTRA_NO_MUSIC = "no_music"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_SOURCE = "source"
        private val nextId = AtomicInteger(100)

        /** Downloads [url]; with [noMusic] saves only a copy without music */
        fun start(c: Context, url: String, audio: Boolean, noMusic: Boolean = false) {
            launch(c, Intent(c, DownloadService::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_AUDIO, audio).putExtra(EXTRA_NO_MUSIC, noMusic))
        }

        /** Removes the music from a file already on the phone (a download, or a shared video) */
        fun startLocal(c: Context, uri: Uri, title: String, mime: String, source: Source) {
            launch(c, Intent(c, DownloadService::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_MIME, mime).putExtra(EXTRA_SOURCE, source.name))
        }

        private fun launch(c: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
    }

    private data class Job(
        val url: String?, val audio: Boolean, val noMusic: Boolean,
        val local: Uri? = null, val title: String = "", val mime: String = "", val source: Source = Source.OTHER,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = Channel<Job>(Channel.UNLIMITED)
    private var pending = 0
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT))
        }
        goForeground(progressNotif(getString(R.string.preparing), -1))
        scope.launch { for (job in queue) process(job) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val local = intent?.data
        if (intent == null || (url == null && local == null)) { if (pending == 0) stopSelf(); return START_NOT_STICKY }
        goForeground(progressNotif(getString(R.string.preparing), -1))
        pending++
        queue.trySend(if (local != null) {
            val mime = intent.getStringExtra(EXTRA_MIME) ?: contentResolver.getType(local).orEmpty()
            Job(null, mime.startsWith("audio"), true, local,
                intent.getStringExtra(EXTRA_TITLE).orEmpty(), mime,
                Source.of(intent.getStringExtra(EXTRA_SOURCE).orEmpty()))
        } else {
            Job(url, intent.getBooleanExtra(EXTRA_AUDIO, false), intent.getBooleanExtra(EXTRA_NO_MUSIC, false))
        })
        return START_NOT_STICKY
    }

    private suspend fun process(job: Job) {
        val title = when {
            job.url == null -> getString(R.string.bg_nm_title)
            job.noMusic -> getString(R.string.bg_nm_dl_title, Source.fromUrl(job.url).label(this))
            else -> getString(if (job.audio) R.string.bg_audio_title else R.string.bg_video_title,
                Source.fromUrl(job.url).label(this))
        }
        val onEvent: (Downloader.Event) -> Unit = { ev ->
            val n = when (ev) {
                is Downloader.Event.Status -> progressNotif(title + "\n" + ev.text, -1)
                is Downloader.Event.Progress ->
                    progressNotif(title + (ev.label?.let { "\n" + it } ?: ""), ev.percent)
            }
            nm.notify(FG_ID, n)
        }
        try {
            val saved = if (job.url != null) {
                Downloader.download(this, job.url, job.audio, "bg", job.noMusic, onEvent)
            } else {
                listOf(removeMusicLocal(job, onEvent))
            }
            doneNotif(saved.first(), job.noMusic)
        } catch (e: Exception) {
            nm.notify(nextId.getAndIncrement(), NotificationCompat.Builder(this, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(getString(R.string.bg_failed))
                .setContentText(e.message?.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.failed, e.message?.take(300).orEmpty())))
                .setAutoCancel(true).build())
        } finally {
            pending--
            if (pending <= 0) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun goForeground(n: Notification) {
        ServiceCompat.startForeground(this, FG_ID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    /** Music removal for a file already on the phone: copy → process → save next to the downloads */
    private suspend fun removeMusicLocal(job: Job, onEvent: (Downloader.Event) -> Unit): DownloadItem {
        val ext = when {
            job.mime == "audio/mpeg" -> "mp3"
            job.audio -> "m4a"
            job.mime == "video/webm" -> "webm"
            else -> "mp4"
        }
        val base = job.title.ifBlank { "video" }.map { if (it in "\\/:*?\"<>|") '_' else it }
            .joinToString("").take(70)
        val input = withContext(Dispatchers.IO) {
            MusicRemover.copyToCache(this@DownloadService, job.local!!, "$base.$ext")
        }
        try {
            val out = Downloader.removeMusic(this, input, job.audio, onEvent)
            return withContext(Dispatchers.IO) {
                Downloader.save(this@DownloadService, out, job.source).also {
                    HistoryStore.add(this@DownloadService, it)
                    out.parentFile?.deleteRecursively()
                }
            }
        } finally {
            input.parentFile?.deleteRecursively()
        }
    }

    private fun progressNotif(text: String, percent: Int): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(text.substringBefore('\n'))
            .setContentText(text.substringAfter('\n', "").ifEmpty { if (percent >= 0) "$percent%" else "" })
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .build()

    private fun doneNotif(item: DownloadItem, noMusic: Boolean) {
        val open = Intent(this, PlayerActivity::class.java)
            .putExtra("uri", item.uri).putExtra("title", item.title).putExtra("mime", item.mime)
            .putExtra("source", item.source.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = nextId.getAndIncrement()
        val pi = PendingIntent.getActivity(this, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(id, NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(when {
                noMusic -> R.string.bg_done_nm
                item.isAudio -> R.string.bg_done_audio
                else -> R.string.bg_done_video
            }))
            .setContentText(item.title)
            .setContentIntent(pi)
            .addAction(0, getString(if (item.isAudio) R.string.listen else R.string.watch), pi)
            .setAutoCancel(true).build())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
