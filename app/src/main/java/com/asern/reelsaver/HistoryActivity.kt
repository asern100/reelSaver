package com.asern.reelsaver

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File

/** صفحة «تحميلاتي»: كل اللي تحمّل، بطاغ المصدر وفلتر فيديو/صوت */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var filter: MaterialButtonToggleGroup
    private var items: List<DownloadItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        filter = findViewById(R.id.filter)
        list.layoutManager = LinearLayoutManager(this)
        filter.check(R.id.fAll)
        filter.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val all = HistoryStore.load(this)
        items = when (filter.checkedButtonId) {
            R.id.fVideo -> all.filter { !it.isAudio }
            R.id.fAudio -> all.filter { it.isAudio }
            else -> all
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = Adapter()
    }

    private fun confirmDelete(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage(R.string.delete_q)
            .setPositiveButton(R.string.delete_file) { _, _ ->
                val uri = Uri.parse(item.uri)
                try {
                    if (uri.scheme == "file") File(uri.path!!).delete()
                    else contentResolver.delete(uri, null, null)
                } catch (_: Exception) { /* الملف تنحّى من قبل ولا ما عندناش الحق */ }
                HistoryStore.remove(this, item); refresh()
            }
            .setNeutralButton(R.string.remove_from_list) { _, _ ->
                HistoryStore.remove(this, item); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.typeIcon)
        val title: TextView = v.findViewById(R.id.itemTitle)
        val tag: TextView = v.findViewById(R.id.sourceTag)
        val meta: TextView = v.findViewById(R.id.itemMeta)
    }

    inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            Holder(LayoutInflater.from(p.context).inflate(R.layout.item_download, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: Holder, i: Int) {
            val d = items[i]
            h.icon.text = when { d.isAudio -> "🎵"; d.isImage -> "🖼"; else -> "🎬" }
            h.title.text = d.title
            h.tag.showSourceTag(d.source)
            val type = getString(when { d.isAudio -> R.string.audio; d.isImage -> R.string.image; else -> R.string.video })
            h.meta.text = type + " • " + DateUtils.getRelativeTimeSpanString(d.time)
            h.itemView.setOnClickListener { openDownload(d) }
            h.itemView.setOnLongClickListener { confirmDelete(d); true }
        }
    }
}
