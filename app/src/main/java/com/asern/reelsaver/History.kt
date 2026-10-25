package com.asern.reelsaver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where the video came from, detected from the link */
enum class Source(private val name_: String?, val colorRes: Int) {
    TIKTOK("TikTok", R.color.src_tiktok),
    INSTAGRAM("Instagram", R.color.src_instagram),
    FACEBOOK("Facebook", R.color.src_facebook),
    YOUTUBE("YouTube", R.color.src_youtube),
    OTHER(null, R.color.src_other);

    fun label(c: Context): String = name_ ?: c.getString(R.string.source_other)

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

/** Styles a TextView as a soft colored pill (iOS style) */
fun TextView.showSourceTag(s: Source) {
    val c = ContextCompat.getColor(context, s.colorRes)
    text = s.label(context)
    setTextColor(c)
    textSize = 12f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val d = resources.displayMetrics.density
    setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
    background = GradientDrawable().apply {
        cornerRadius = 6 * d
        setColor((c and 0x00FFFFFF) or 0x24000000)
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

/** Download history stored as JSON inside the app */
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

/** Opens video/audio in the built-in player, images in the gallery */
fun Context.openDownload(item: DownloadItem) {
    if (item.isImage) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(item.uri), item.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cant_open_image, Toast.LENGTH_SHORT).show()
        }
        return
    }
    startActivity(Intent(this, PlayerActivity::class.java).apply {
        putExtra("uri", item.uri)
        putExtra("title", item.title)
        putExtra("mime", item.mime)
        putExtra("source", item.source.name)
    })
}
