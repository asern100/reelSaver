package com.asern.reelsaver

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** iOS-style segmented control */
class Segmented @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : LinearLayout(c, a) {

    var onChange: ((Int) -> Unit)? = null
    private val items = mutableListOf<TextView>()

    var selected = 0
        set(v) { field = v; render() }

    init {
        orientation = HORIZONTAL
        val p = dp(2)
        setPadding(p, p, p, p)
        background = round(R.color.seg_track, 9)
    }

    fun setItems(labels: List<String>) {
        removeAllViews(); items.clear()
        labels.forEachIndexed { i, t ->
            val tv = TextView(context).apply {
                text = t
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ContextCompat.getColor(context, R.color.label))
                setOnClickListener {
                    if (this@Segmented.isEnabled && selected != i) { selected = i; onChange?.invoke(i) }
                }
            }
            addView(tv, LayoutParams(0, dp(32), 1f))
            items.add(tv)
        }
        render()
    }

    private fun render() {
        items.forEachIndexed { i, tv ->
            val on = i == selected
            tv.background = if (on) round(R.color.seg_thumb, 7) else null
            tv.elevation = if (on) dp(2).toFloat() else 0f
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
