package com.asern.reelsaver

import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

/** iOS-style settings table: appearance, floating buttons, download engine */
class SettingsActivity : AppCompatActivity() {

    private lateinit var box: LinearLayout
    private var engineMsg: String? = null
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        box = findViewById(R.id.box)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        box.removeAllViews()

        section(getString(R.string.appearance)) {
            value(getString(R.string.theme), themeLabels()[themeIndex()]) { chooseTheme() }
            value(getString(R.string.language), langLabels()[langIndex()]) { chooseLanguage() }
        }

        val bubbleOn = isBubbleServiceOn()
        section(
            getString(R.string.bubble_section),
            footer = if (bubbleOn) getString(R.string.bubble_status_on)
                     else getString(R.string.bubble_status_off) + "\n\n" + getString(R.string.bubble_restricted),
        ) {
            switch(getString(R.string.bubble_show), BubbleService.isEnabled(this@SettingsActivity)) {
                BubbleService.setEnabled(this@SettingsActivity, it)
            }
            if (!bubbleOn) {
                action(getString(R.string.bubble_enable)) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                action(getString(R.string.app_info)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }

        val last = Engine.lastUpdate(this)
        section(getString(R.string.engine_section), footer = engineMsg) {
            switch(getString(R.string.auto_update), Engine.isAutoUpdate(this@SettingsActivity)) {
                Engine.setAutoUpdate(this@SettingsActivity, it)
            }
            value(getString(R.string.version), Engine.version(this@SettingsActivity), null)
            value(getString(R.string.last_update),
                if (last == 0L) getString(R.string.never) else DateUtils.getRelativeTimeSpanString(last).toString(), null)
            action(getString(if (updating) R.string.updating else R.string.update), enabled = !updating) { updateNow() }
        }

        box.addView(TextView(this).apply {
            text = getString(R.string.about_version, packageManager.getPackageInfo(packageName, 0).versionName)
            setTextColor(color(R.color.label2))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })
    }

    // ---------- Actions ----------

    private fun themeLabels() = arrayOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
    private val themeModes = intArrayOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
    private fun themeIndex() = themeModes.indexOf(UiPrefs.nightMode(this)).coerceAtLeast(0)

    private fun chooseTheme() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(themeLabels(), themeIndex()) { d, which ->
                d.dismiss()
                UiPrefs.setNightMode(this, themeModes[which])
            }
            .show()
    }

    private fun langLabels() = arrayOf(getString(R.string.lang_system), getString(R.string.lang_en), getString(R.string.lang_ar))
    private val langTags = arrayOf("", "en", "ar")
    private fun langIndex(): Int {
        val cur = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when { cur.startsWith("ar") -> 2; cur.startsWith("en") -> 1; else -> 0 }
    }

    private fun chooseLanguage() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(langLabels(), langIndex()) { d, which ->
                d.dismiss()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langTags[which]))
            }
            .show()
    }

    private fun updateNow() {
        updating = true; engineMsg = null; build()
        lifecycleScope.launch {
            engineMsg = Engine.update(this@SettingsActivity).message
            updating = false
            build()
        }
    }

    private fun isBubbleServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, BubbleService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---------- iOS table builder ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private inner class Section {
        val rows = mutableListOf<View>()

        private fun row(): LinearLayout = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50)
            setPadding(dp(16), dp(6), dp(12), dp(6))
        }

        private fun title(t: String, c: Int = R.color.label) = TextView(this@SettingsActivity).apply {
            text = t; textSize = 16f; setTextColor(color(c))
        }

        private fun clickable(v: View, onClick: () -> Unit) {
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            v.foreground = ContextCompat.getDrawable(this@SettingsActivity, tv.resourceId)
            v.setOnClickListener { onClick() }
        }

        fun value(t: String, v: String, onClick: (() -> Unit)?) {
            val r = row()
            r.addView(title(t), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(TextView(this@SettingsActivity).apply {
                text = v; textSize = 16f; setTextColor(color(R.color.label2)); maxLines = 1
            })
            if (onClick != null) {
                r.addView(ImageView(this@SettingsActivity).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(color(R.color.label3))
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(6) })
                clickable(r, onClick)
            }
            rows.add(r)
        }

        fun switch(t: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            val r = row()
            r.addView(title(t), LinearLayout.LayoutParams(0, -2, 1f))
            val sw = MaterialSwitch(this@SettingsActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            }
            r.addView(sw)
            clickable(r) { sw.toggle() }
            rows.add(r)
        }

        fun action(t: String, enabled: Boolean = true, onClick: () -> Unit) {
            val r = row()
            r.addView(title(t, if (enabled) R.color.tint else R.color.label2).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            if (enabled) clickable(r, onClick)
            rows.add(r)
        }
    }

    private fun section(header: String, footer: String? = null, fill: Section.() -> Unit) {
        box.addView(TextView(this).apply {
            text = header.uppercase()
            textSize = 13f
            setTextColor(color(R.color.label2))
            letterSpacing = 0.02f
            setPadding(dp(16), dp(28), dp(16), dp(7))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            clipToOutline = true
        }
        val s = Section().apply(fill)
        s.rows.forEachIndexed { i, v ->
            card.addView(v)
            if (i < s.rows.size - 1) card.addView(View(this).apply {
                setBackgroundColor(color(R.color.separator))
            }, LinearLayout.LayoutParams(-1, dp(1) / 2 + 1).apply { marginStart = dp(16) })
        }
        box.addView(card)
        if (!footer.isNullOrBlank()) box.addView(TextView(this).apply {
            text = footer
            textSize = 13f
            setTextColor(color(R.color.label2))
            setPadding(dp(16), dp(7), dp(16), 0)
        })
    }
}
