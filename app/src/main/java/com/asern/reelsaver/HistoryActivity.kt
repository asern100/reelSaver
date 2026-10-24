package com.asern.reelsaver

import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Downloads screen: iOS-style grouped list with source pills and a Video/Audio filter */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var filter: Segmented
    private var items: List<DownloadItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        filter = findViewById(R.id.filter)
        list.layoutManager = LinearLayoutManager(this)
        filter.setItems(listOf(getString(R.string.all), getString(R.string.filter_video), getString(R.string.filter_audio)))
        filter.onChange = { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val all = HistoryStore.load(this)
        items = when (filter.selected) {
            1 -> all.filter { !it.isAudio }
            2 -> all.filter { it.isAudio }
            else -> all
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hint).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        list.adapter = Adapter()
    }

    private fun confirmDelete(item: DownloadItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setMessage(R.string.delete_q)
            .setPositiveButton(R.string.delete_file) { _, _ ->
                val uri = Uri.parse(item.uri)
                try {
                    if (uri.scheme == "file") File(uri.path!!).delete()
                    else contentResolver.delete(uri, null, null)
                } catch (_: Exception) { /* already deleted, or no permission after reinstall */ }
                HistoryStore.remove(this, item); refresh()
            }
            .setNeutralButton(R.string.remove_from_list) { _, _ ->
                HistoryStore.remove(this, item); refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val tile: View = v.findViewById(R.id.iconTile)
        val icon: ImageView = v.findViewById(R.id.typeIcon)
        val title: TextView = v.findViewById(R.id.itemTitle)
        val tag: TextView = v.findViewById(R.id.sourceTag)
        val meta: TextView = v.findViewById(R.id.itemMeta)
        val sep: View = v.findViewById(R.id.separator)
    }

    inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            Holder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, i: Int) {
            val d = items[i]
            val first = i == 0
            val last = i == items.size - 1

            // One rounded group: only the outer corners are rounded
            val r = dp(12f)
            h.itemView.background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@HistoryActivity, R.color.card))
                val t = if (first) r else 0f
                val b = if (last) r else 0f
                cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
            }
            h.itemView.clipToOutline = true
            h.sep.visibility = if (last) View.GONE else View.VISIBLE

            val (iconRes, colorRes) = when {
                d.isAudio -> R.drawable.ic_audio to R.color.purple
                d.isImage -> R.drawable.ic_image to R.color.orange
                else -> R.drawable.ic_film to R.color.blue
            }
            h.icon.setImageResource(iconRes)
            h.tile.background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@HistoryActivity, colorRes))
                cornerRadius = dp(10f)
            }
            h.title.text = d.title
            h.tag.showSourceTag(d.source)
            h.meta.text = DateUtils.getRelativeTimeSpanString(d.time)
            h.itemView.setOnClickListener { openDownload(d) }
            h.itemView.setOnLongClickListener { confirmDelete(d); true }
        }
    }
}
