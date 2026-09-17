package com.lazyneil.lazycar

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

private const val BT_ICON = android.R.drawable.stat_sys_data_bluetooth

class MainActivity : AppCompatActivity() {

    private lateinit var p: Prefs
    private var players = listOf<Triple<String, String, Drawable?>>()   // pkg,label,icon
    private var audioDevices = listOf<Triple<String, String, Int>>()    // mac,name,iconRes
    private var allDevices = listOf<Triple<String, String, Int>>()

    private var selPlayer = ""
    private var selMac1 = ""; private var selName1 = ""
    private var selMac2 = ""; private var selName2 = ""
    private var selMac3 = ""; private var selName3 = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        DynamicColorsCompat(this)
        p = Prefs(this)
        if (p.amoled) theme.applyStyle(R.style.ThemeOverlay_LazyCar_Amoled, true) // black surfaces, keep dynamic accents
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        selPlayer = p.playerPkg
        selMac1 = p.mac1; selName1 = p.name1
        selMac2 = p.mac2; selName2 = p.name2
        selMac3 = p.mac3; selName3 = p.name3

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, b.top, v.paddingRight, b.bottom)
            insets
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }

        buildPlayers()
        buildDevices()
        buildHeadunit()

        findViewById<MaterialSwitch>(R.id.dualSwitch).isChecked = p.dualAudio
        findViewById<MaterialSwitch>(R.id.amoledSwitch).apply {
            isChecked = p.amoled
            setOnCheckedChangeListener { _, on -> if (on != p.amoled) { p.amoled = on; recreate() } }
        }
        findViewById<MaterialButton>(R.id.goButton).setOnClickListener { save(); GoAction.run(applicationContext) }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.accessibilityBtn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val on = accessibilityEnabled()
        findViewById<MaterialButton>(R.id.accessibilityBtn).text =
            if (on) "Auto-tap: on ✓" else "Auto-tap: off — tap to enable"
    }

    private fun accessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { it.substringBefore('/') == packageName }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        buildDevices()
    }

    // ---- players ----
    private fun buildPlayers() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        players = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .map { pkg ->
                Triple(pkg,
                    try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg },
                    try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null })
            }
            .sortedBy { it.second.lowercase() }
        renderPlayer()
        findViewById<View>(R.id.playerRow).setOnClickListener { showPlayerPicker() }
    }

    private fun renderPlayer() {
        val icon = findViewById<ImageView>(R.id.playerIcon)
        val value = findViewById<TextView>(R.id.playerValue)
        val cur = players.firstOrNull { it.first == selPlayer }
        if (cur != null) { icon.setImageDrawable(cur.third); value.text = cur.second }
        else { icon.setImageDrawable(null); value.text = "Choose a player" }
    }

    private fun showPlayerPicker() {
        if (players.isEmpty()) { toast("No music players found"); return }
        val adapter = object : ArrayAdapter<Triple<String, String, Drawable?>>(
            this, 0, players) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                val row = cv as? TextView ?: TextView(this@MainActivity).apply {
                    val dp = resources.displayMetrics.density
                    setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                    textSize = 16f
                    compoundDrawablePadding = (16 * dp).toInt()
                }
                val item = players[pos]
                row.text = item.second
                val sz = (36 * resources.displayMetrics.density).toInt()
                item.third?.setBounds(0, 0, sz, sz)
                row.setCompoundDrawables(item.third, null, null, null)
                return row
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Player")
            .setAdapter(adapter) { d, which -> selPlayer = players[which].first; renderPlayer(); d.dismiss() }
            .show()
    }

    // ---- devices ----
    private fun buildDevices() {
        val note = findViewById<TextView>(R.id.btNote)
        val enableBtn = findViewById<MaterialButton>(R.id.btEnable)
        note.visibility = View.GONE; enableBtn.visibility = View.GONE
        audioDevices = emptyList(); allDevices = emptyList()

        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        when {
            !ok -> showNote(note, "Bluetooth permission needed to list paired devices.")
            adapter == null -> showNote(note, "No Bluetooth on this device.")
            !adapter.isEnabled -> {
                showNote(note, "Bluetooth is off.")
                enableBtn.visibility = View.VISIBLE
                enableBtn.setOnClickListener {
                    @Suppress("DEPRECATION") startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            }
            else -> {
                val bonded = try { adapter.bondedDevices.toList() } catch (e: SecurityException) { emptyList() }
                if (bonded.isEmpty()) showNote(note, "No paired devices.")
                allDevices = bonded.map { Triple(it.address, it.name ?: it.address, iconFor(it)) }
                val audio = bonded.filter { isAudio(it) }
                audioDevices = (if (audio.isEmpty()) bonded else audio)
                    .map { Triple(it.address, it.name ?: it.address, iconFor(it)) }
            }
        }

        bindDeviceRow(R.id.dev1Row, R.id.dev1Icon, R.id.dev1Value, "Device 1",
            { audioDevices }, { selMac1 }, { m, n -> selMac1 = m; selName1 = n })
        bindDeviceRow(R.id.dev2Row, R.id.dev2Icon, R.id.dev2Value, "Device 2",
            { audioDevices }, { selMac2 }, { m, n -> selMac2 = m; selName2 = n })
        bindDeviceRow(R.id.dev3Row, R.id.dev3Icon, R.id.dev3Value, "Head unit tablet (optional)",
            { allDevices }, { selMac3 }, { m, n -> selMac3 = m; selName3 = n })
    }

    private fun showNote(note: TextView, msg: String) { note.text = msg; note.visibility = View.VISIBLE }

    private fun bindDeviceRow(rowId: Int, iconId: Int, valueId: Int, default: String,
        list: () -> List<Triple<String, String, Int>>, getMac: () -> String,
        set: (String, String) -> Unit) {
        renderDevice(iconId, valueId, default, getMac(), list())
        findViewById<View>(rowId).setOnClickListener {
            val items = list()
            if (items.isEmpty()) { toast("No paired devices available"); return@setOnClickListener }
            val names = items.map { it.second }.toTypedArray()
            val checked = items.indexOfFirst { it.first == getMac() }
            MaterialAlertDialogBuilder(this)
                .setTitle(default.substringBefore(" (").ifEmpty { "Device" })
                .setSingleChoiceItems(names, checked) { d, which ->
                    set(items[which].first, items[which].second)
                    renderDevice(iconId, valueId, default, items[which].first, items)
                    d.dismiss()
                }
                .setNeutralButton("Clear") { _, _ ->
                    set("", ""); renderDevice(iconId, valueId, default, "", items)
                }
                .show()
        }
    }

    private fun renderDevice(iconId: Int, valueId: Int, default: String, mac: String,
        list: List<Triple<String, String, Int>>) {
        val icon = findViewById<ImageView>(iconId)
        val value = findViewById<TextView>(valueId)
        if (mac.isEmpty()) {
            icon.setImageDrawable(null); value.text = default
        } else {
            val res = list.firstOrNull { it.first == mac }?.third ?: BT_ICON
            icon.setImageResource(res)
            value.text = list.firstOrNull { it.first == mac }?.second ?: mac
        }
    }

    private fun isAudio(d: BluetoothDevice): Boolean {
        val major = try { d.bluetoothClass?.majorDeviceClass } catch (e: SecurityException) { null }
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
        val uuids = try { d.uuids } catch (e: SecurityException) { null } ?: return false
        return uuids.any { it.uuid.toString().lowercase() in AUDIO_UUIDS }
    }

    private fun iconFor(d: BluetoothDevice): Int {
        val dc = try { d.bluetoothClass?.deviceClass } catch (e: SecurityException) { null }
        return when (dc) {
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> R.drawable.ic_car
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> R.drawable.ic_headphones
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> R.drawable.ic_speaker
            else -> BT_ICON
        }
    }

    private fun buildHeadunit() {
        findViewById<MaterialSwitch>(R.id.headunitSwitch).isChecked = p.headunit
        findViewById<TextInputEditText>(R.id.ipField).setText(p.headunitIp)
        val d = GoAction.detectHeadunit(this)
        findViewById<TextView>(R.id.headunitDetected).text =
            if (d != null) "Detected: ${d.second}" else "Detected: none installed on phone"
    }

    private fun save() {
        p.playerPkg = selPlayer
        p.mac1 = selMac1; p.name1 = selName1
        p.mac2 = selMac2; p.name2 = selName2
        p.mac3 = selMac3; p.name3 = selName3
        p.headunit = findViewById<MaterialSwitch>(R.id.headunitSwitch).isChecked
        p.headunitIp = findViewById<TextInputEditText>(R.id.ipField).text.toString().trim()
        p.dualAudio = findViewById<MaterialSwitch>(R.id.dualSwitch).isChecked
        p.amoled = findViewById<MaterialSwitch>(R.id.amoledSwitch).isChecked
        toast("Saved")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        private val AUDIO_UUIDS = setOf(
            "0000110b-0000-1000-8000-00805f9b34fb",
            "0000111e-0000-1000-8000-00805f9b34fb",
            "0000184e-0000-1000-8000-00805f9b34fb"
        )
    }
}

/** DynamicColors without importing when unavailable; keeps onCreate tidy. */
private fun DynamicColorsCompat(a: AppCompatActivity) =
    com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(a)
