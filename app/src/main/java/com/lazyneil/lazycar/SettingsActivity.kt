package com.lazyneil.lazycar

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var p: Prefs
    private var selMac3 = ""; private var selName3 = ""
    private var allDevices = listOf<Triple<String, String, Int>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        p = Prefs(this)
        if (p.amoled) theme.applyStyle(R.style.ThemeOverlay_LazyCar_Amoled, true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        selMac3 = p.mac3; selName3 = p.name3

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<MaterialSwitch>(R.id.dualSwitch).apply {
            isChecked = p.dualAudio
            setOnCheckedChangeListener { _, on -> p.dualAudio = on }
        }
        findViewById<MaterialSwitch>(R.id.amoledSwitch).apply {
            isChecked = p.amoled
            setOnCheckedChangeListener { _, on -> if (on != p.amoled) { p.amoled = on; recreate() } }
        }

        val extra = findViewById<View>(R.id.headunitExtra)
        findViewById<MaterialSwitch>(R.id.headunitSwitch).apply {
            isChecked = p.headunit
            extra.visibility = if (p.headunit) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on -> p.headunit = on; extra.visibility = if (on) View.VISIBLE else View.GONE }
        }
        val d = GoAction.detectHeadunit(this)
        findViewById<TextView>(R.id.headunitDetected).text =
            "Detected: " + (d?.second ?: "none")
        findViewById<TextInputEditText>(R.id.ipField).setText(p.headunitIp)

        val mobile = findViewById<MaterialSwitch>(R.id.mobileSwitch)
        findViewById<MaterialSwitch>(R.id.hotspotSwitch).apply {
            isChecked = p.hotspot
            mobile.visibility = if (p.hotspot) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, on -> p.hotspot = on; mobile.visibility = if (on) View.VISIBLE else View.GONE }
        }
        mobile.apply {
            isChecked = p.mobileData
            setOnCheckedChangeListener { _, on -> p.mobileData = on }
        }

        buildHeadunitPicker()
        applyAccent()
        findViewById<View>(R.id.accentRow).setOnClickListener { showAccentDialog() }
        findViewById<View>(R.id.helpRow).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        val v = try { packageManager.getPackageInfo(packageName, 0) } catch (e: Exception) { null }
        findViewById<TextView>(R.id.versionText).text =
            if (v != null) "LazyCar v${v.versionName} (${v.longVersionCode})" else "LazyCar"
    }

    override fun onResume() {
        super.onResume()
        refreshA11y()
    }

    override fun onPause() {
        super.onPause()
        p.headunitIp = findViewById<TextInputEditText>(R.id.ipField).text.toString().trim()
    }

    private fun refreshA11y() {
        val on = accessibilityEnabled()
        val icon = findViewById<ImageView>(R.id.a11yIcon)
        val text = findViewById<TextView>(R.id.a11yText)
        val row = findViewById<View>(R.id.a11yRow)
        if (on) {
            icon.setImageResource(R.drawable.ic_check_circle)
            icon.setColorFilter(getColor(R.color.accent))
            text.text = "Auto-tap ready"
            row.setOnClickListener(null); row.isClickable = false
        } else {
            icon.setImageResource(R.drawable.ic_error_outline)
            icon.setColorFilter(getColor(R.color.errorRed))
            text.text = "Auto-tap off. Tap to set up"
            row.isClickable = true
            row.setOnClickListener { openAccessibilitySettings() }
        }
    }

    private fun buildHeadunitPicker() {
        val r = BtDevices.load(this)
        allDevices = r.all
        renderDev3()
        findViewById<View>(R.id.dev3Row).setOnClickListener {
            if (allDevices.isEmpty()) { toast(r.note ?: "No paired devices available"); return@setOnClickListener }
            val names = allDevices.map { it.second }.toTypedArray()
            val checked = allDevices.indexOfFirst { it.first == selMac3 }
            MaterialAlertDialogBuilder(this)
                .setTitle("Head unit tablet")
                .setSingleChoiceItems(names, checked) { dlg, which ->
                    selMac3 = allDevices[which].first; selName3 = allDevices[which].second
                    p.mac3 = selMac3; p.name3 = selName3; renderDev3(); dlg.dismiss()
                }
                .setNeutralButton("Clear") { _, _ ->
                    selMac3 = ""; selName3 = ""; p.mac3 = ""; p.name3 = ""; renderDev3()
                }
                .show()
        }
    }

    private fun renderDev3() {
        val icon = findViewById<ImageView>(R.id.dev3Icon)
        val value = findViewById<TextView>(R.id.dev3Value)
        if (selMac3.isEmpty()) { icon.setImageResource(BtDevices.ICON); value.text = "Head unit tablet" }
        else {
            icon.setImageResource(allDevices.firstOrNull { it.first == selMac3 }?.third ?: BtDevices.ICON)
            value.text = allDevices.firstOrNull { it.first == selMac3 }?.second ?: selName3
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Tint the accent-driven controls (switches + the row preview dot). */
    private fun applyAccent() {
        val accent = Accent.color(this)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val track = ColorStateList(states, intArrayOf(accent, 0xFF3A3F45.toInt()))
        val thumb = ColorStateList(states, intArrayOf(Accent.onColor(accent), 0xFF9AA0A6.toInt()))
        intArrayOf(R.id.dualSwitch, R.id.amoledSwitch, R.id.headunitSwitch,
            R.id.hotspotSwitch, R.id.mobileSwitch).forEach {
            findViewById<MaterialSwitch>(it).apply { trackTintList = track; thumbTintList = thumb }
        }
        findViewById<View>(R.id.accentDot).backgroundTintList = ColorStateList.valueOf(accent)
    }

    private fun showAccentDialog() {
        val grid = GridLayout(this).apply { columnCount = 4; val pad = dp(12); setPadding(pad, pad, pad, pad) }
        val cur = p.accent
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        Accent.swatches.forEach { (label, value) ->
            val fill = if (value == 0) Accent.systemColor(this) else value
            grid.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(fill)
                    if (value == cur) setStroke(dp(3), Color.WHITE) else setStroke(dp(1), 0x55FFFFFF)
                }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(40); height = dp(40); setMargins(dp(10), dp(10), dp(10), dp(10))
                }
                contentDescription = label
                setOnClickListener {
                    p.accent = value; GoWidget.refresh(this@SettingsActivity); dialog.dismiss(); recreate()
                }
            })
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Accent color")
            .setView(grid)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun openAccessibilitySettings() {
        val cn = ComponentName(this, TapService::class.java).flattenToString()
        try {
            val args = Bundle().apply { putString(":settings:fragment_args_key", cn) }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", cn)
                .putExtra(":settings:show_fragment_args", args))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun accessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { it.substringBefore('/') == packageName }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
