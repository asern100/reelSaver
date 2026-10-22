package com.asern.reelsaver

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
    private lateinit var bubbleStatus: TextView
    private lateinit var bubbleEnableBtn: Button

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

        // Floating buttons (accessibility service)
        bubbleStatus = findViewById(R.id.bubbleStatus)
        bubbleEnableBtn = findViewById(R.id.bubbleEnableBtn)
        bubbleEnableBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.appInfoBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<MaterialSwitch>(R.id.bubbleShow).apply {
            isChecked = BubbleService.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> BubbleService.setEnabled(this@SettingsActivity, on) }
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

    override fun onResume() {
        super.onResume()
        val on = isBubbleServiceOn()
        bubbleStatus.text = getString(if (on) R.string.bubble_status_on else R.string.bubble_status_off)
        bubbleEnableBtn.visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bubbleRestricted).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.appInfoBtn).visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun isBubbleServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, BubbleService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun showEngineInfo() {
        val last = Engine.lastUpdate(this)
        val when_ = if (last == 0L) getString(R.string.never)
                    else DateUtils.getRelativeTimeSpanString(last).toString()
        engineInfo.text = getString(R.string.engine_info, Engine.version(this), when_)
    }
}
