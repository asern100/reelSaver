package com.asern.reelsaver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** منين جا الفيديو — يتعرف من الرابط */
enum class Source(val label: String, val color: Int) {
    TIKTOK("TikTok", 0xFF111111.toInt()),
    INSTAGRAM("Instagram", 0xFFC13584.toInt()),
    FACEBOOK("Facebook", 0xFF1877F2.toInt()),
    YOUTUBE("YouTube", 0xFFE62117.toInt()),
    OTHER("أخرى", 0xFF666666.toInt());

    companion object {
        fun fromUrl(url: String): Source {
            val u = url.lowercase()
            return when {
                "tiktok" in u -> TIKTOK
                "instagram" in u || "instagr.am" in u -> INSTAGRAM
                "facebook" in u || "fb.watch" in u || "fb.com" in u -> FACEBOOK
                "youtube" in u || "youtu.be" in u -> YOUTUBE
                else -> OTHER
            }
        }
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** يلوّن TextView كطاغ (chip) بلون المصدر */
fun TextView.showSourceTag(s: Source) {
    text = "● " + s.label
    setTextColor(0xFFFFFFFF.toInt())
    background = GradientDrawable().apply {
        cornerRadius = 40f
        setColor(s.color)
    }
}

data class DownloadItem(
    val title: String,
    val uri: String,
    val mime: String,
    val source: Source,
    val time: Long,
) {
    val isAudio get() = mime.startsWith("audio")
    val isImage get() = mime.startsWith("image")
}

/** سجل التحميلات محفوظ في ملف JSON داخل التطبيق */
object HistoryStore {
    private fun file(c: Context) = File(c.filesDir, "history.json")

    fun load(c: Context): MutableList<DownloadItem> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                DownloadItem(
                    o.getString("title"), o.getString("uri"), o.getString("mime"),
                    Source.of(o.optString("source")), o.getLong("time"),
                )
            }
        } catch (e: Exception) { mutableListOf() }
    }

    private fun save(c: Context, list: List<DownloadItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("title", it.title); put("uri", it.uri); put("mime", it.mime)
                put("source", it.source.name); put("time", it.time)
            })
        }
        file(c).writeText(arr.toString())
    }

    fun add(c: Context, item: DownloadItem) = save(c, load(c).apply { add(0, item) })

    fun remove(c: Context, item: DownloadItem) =
        save(c, load(c).filterNot { it.uri == item.uri && it.time == item.time })
}

/** يحل الفيديو ولا الصوت في المشغّل، والصور في الغاليري */
fun Context.openDownload(item: DownloadItem) {
    if (item.isImage) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(item.uri), item.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "ما نجمتش نحل الصورة", Toast.LENGTH_SHORT).show()
        }
        return
    }
    startActivity(Intent(this, PlayerActivity::class.java).apply {
        putExtra("uri", item.uri)
        putExtra("title", item.title)
        putExtra("mime", item.mime)
    })
}
