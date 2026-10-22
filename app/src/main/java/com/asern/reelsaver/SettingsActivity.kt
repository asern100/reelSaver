package com.asern.reelsaver

import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

/** Settings: app language (English / Arabic / phone default) and engine updates */
class SettingsActivity : AppCompatActivity() {

    private lateinit var engineInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<Button>(R.id.backBtn).setOnClickListener { finish() }

        // Language
        val group = findViewById<RadioGroup>(R.id.langGroup)
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        group.check(
            when {
                current.startsWith("ar") -> R.id.langAr
                current.startsWith("en") -> R.id.langEn
                else -> R.id.langSystem
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            val tags = when (id) {
                R.id.langAr -> "ar"
                R.id.langEn -> "en"
                else -> ""
            }
            // Recreates the screens in the chosen language and remembers it
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }

        // Automatic weekly update
        findViewById<MaterialSwitch>(R.id.autoUpdate).apply {
            isChecked = Engine.isAutoUpdate(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Engine.setAutoUpdate(this@SettingsActivity, on) }
        }

        // Engine info + manual update
        engineInfo = findViewById(R.id.engineInfo)
        val status = findViewById<TextView>(R.id.engineStatus)
        val btn = findViewById<Button>(R.id.updateNowBtn)
        btn.setOnClickListener {
            btn.isEnabled = false
            status.text = getString(R.string.updating)
            lifecycleScope.launch {
                status.text = Engine.update(this@SettingsActivity).message
                showEngineInfo()
                btn.isEnabled = true
            }
        }
        showEngineInfo()
    }

    private fun showEngineInfo() {
        val last = Engine.lastUpdate(this)
        val when_ = if (last == 0L) getString(R.string.never)
                    else DateUtils.getRelativeTimeSpanString(last).toString()
        engineInfo.text = getString(R.string.engine_info, Engine.version(this), when_)
    }
}
