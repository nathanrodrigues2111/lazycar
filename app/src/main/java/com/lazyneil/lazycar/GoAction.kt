package com.lazyneil.lazycar

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The whole "get in car" sequence, and its undo (STOP). Runs from a foreground GoService so the
 * postDelayed chain and the BT-enable receiver survive independent of any activity. Every step is
 * idempotent: anything already satisfied (BT on, speaker connected, music playing) is skipped.
 * All BT work is best-effort reflection on the profile proxy; failures Toast, never crash.
 */
object GoAction {
    const val IDLE = 0; const val STARTING = 1; const val ON = 2; const val STOPPING = 3
    private const val STUCK_MS = 30000L
    val main = Handler(Looper.getMainLooper())

    private fun log(m: String) = android.util.Log.e("LazyCar", m)
    private fun step(name: String, since: Long) = log("step $name took ${SystemClock.uptimeMillis() - since}ms")

    /** Current state, with recovery: STARTING/STOPPING older than 30s is treated as IDLE. */
    fun effectiveState(p: Prefs): Int {
        val s = p.state
        if ((s == STARTING || s == STOPPING) && System.currentTimeMillis() - p.stateTs > STUCK_MS) return IDLE
        return s
    }
    private fun setState(ctx: Context, p: Prefs, st: Int) {
        p.state = st; GoWidget.refresh(ctx); log("state -> $st")
    }

    fun sequenceDuration(p: Prefs): Long {
        val shareEnd = if (p.dualAudio && p.mac1.isNotEmpty() && p.mac2.isNotEmpty()) 3000L + 6500L else 0L
        return maxOf(5000L, shareEnd) + 1500L
    }

    /** Entry for GO. Returns ms the caller (service) should stay alive for the delayed steps. */
    fun run(ctx: Context): Long {
        val p = Prefs(ctx)
        setState(ctx, p, STARTING)
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        val state = try { adapter?.state ?: BluetoothAdapter.STATE_OFF } catch (e: Exception) { BluetoothAdapter.STATE_OFF }
        if (adapter != null && state != BluetoothAdapter.STATE_ON) {
            enableBtThenRun(ctx, adapter, p, prompt = state != BluetoothAdapter.STATE_TURNING_ON)
            return 12000L + 1500L + sequenceDuration(p)
        }
        log("skipped BT enable (already on)")
        runSequence(ctx, p)
        return sequenceDuration(p)
    }

    /** Idempotent: connect only what's disconnected, launch/play only if music isn't already active. */
    fun runSequence(ctx: Context, p: Prefs) {
        val t0 = SystemClock.uptimeMillis()
        a2dpConnectedDevices(ctx) { connected ->
            step("probe A2DP connections", t0)
            val need1 = p.mac1.isNotEmpty() && p.mac1 !in connected
            val need2 = p.mac2.isNotEmpty() && p.mac2 !in connected
            val need3 = p.headunit && p.mac3.isNotEmpty() && p.mac3 !in connected
            if (need1) connectA2dp(ctx, p.mac1) else if (p.mac1.isNotEmpty()) log("skipped connect ${p.mac1} (connected)")
            if (need2) main.postDelayed({ connectA2dp(ctx, p.mac2) }, 400) else if (p.mac2.isNotEmpty()) log("skipped connect ${p.mac2} (connected)")
            if (need3) main.postDelayed({ connectA2dp(ctx, p.mac3) }, 800)

            val settle = if (need1 || need2) 2000L else 0L
            val musicActive = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive

            if (musicActive) log("skipped player launch (music active)")
            else main.postDelayed({ launchPlayer(ctx, p.playerPkg) }, settle)

            val shareAt = settle + (if (musicActive) 0L else 1000L)
            if (p.dualAudio && p.mac1.isNotEmpty() && p.mac2.isNotEmpty())
                main.postDelayed({ shareAudio(ctx, p.mac1, p.mac2, p.playerPkg) }, shareAt)

            if (musicActive) log("skipped MEDIA_PLAY (music active)")
            else main.postDelayed({ playMedia(ctx) }, shareAt + 1500)

            if (p.headunit) main.postDelayed({ launchHeadunit(ctx, p.headunitIp) }, shareAt + 2000)
            main.postDelayed({ if (p.state == STARTING) { setState(ctx, p, ON); step("GO sequence", t0) } }, sequenceDuration(p))
        }
    }

    /**
     * Android 16 forbids a silent adapter.enable(); we try it, then pop the system REQUEST_ENABLE
     * dialog and let TapService click its confirm button. On STATE_ON we settle 1.5s then run the
     * rest. Gives up after 12s. ponytail: relies on the accessibility service to auto-confirm.
     */
    private fun enableBtThenRun(ctx: Context, adapter: BluetoothAdapter, p: Prefs, prompt: Boolean) {
        val started = AtomicBoolean(false)
        fun go(recv: BroadcastReceiver?) {
            if (!started.compareAndSet(false, true)) return
            recv?.let { try { ctx.unregisterReceiver(it) } catch (e: Exception) {} }
            TapService.disarmButtons()
            log("BT on -> running sequence")
            main.postDelayed({ runSequence(ctx, p) }, 1500)
        }
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) go(this)
            }
        }
        ctx.registerReceiver(recv, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        if (prompt) {
            log("BT off: requesting enable")
            try { @Suppress("DEPRECATION") adapter.enable() } catch (e: Exception) { log("adapter.enable() ${e.message}") }
            TapService.armButtons(listOf("Allow", "Turn on", "Yes", "OK", "Enable"))
            try {
                ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { log("REQUEST_ENABLE ${e.message}") }
        } else log("BT turning on; waiting for STATE_ON")
        if (adapter.isEnabled) go(recv)
        main.postDelayed({
            if (!started.get()) {
                try { ctx.unregisterReceiver(recv) } catch (e: Exception) {}
                TapService.disarmButtons(); setState(ctx, p, IDLE)
                log("BT enable timeout"); toast(ctx, "Bluetooth still off")
            }
        }, 12000)
    }

    /**
     * Opens the OnePlus output switcher and lets TapService add the second speaker to the group.
     * MediaRouter2 can't do it (the app only sees DEFAULT_ROUTE) and the OnePlus startSharing API
     * needs the signature perm com.oplus.permission.safe.BLUETOOTH — see reverse/SHARING_API.md.
     */
    fun shareAudio(ctx: Context, mac1: String, mac2: String, playerPkg: String) {
        try {
            TapService.arm()                    // TapService ticks every "Add device to group." row
            log("shareAudio: armed, opening output switcher")
            openOutputSwitcher(ctx, playerPkg)
            main.postDelayed({
                if (TapService.armedUntil != 0L && !TapService.finishing) {
                    TapService.disarm()
                    log("shareAudio: auto-tap timeout; leaving switcher open")
                    toast(ctx, "Tap second speaker to share")
                }
            }, 6000)
        } catch (e: Exception) {
            log("shareAudio failed ${e.message}"); openOutputSwitcher(ctx, playerPkg)
        }
    }

    private fun openOutputSwitcher(ctx: Context, playerPkg: String) {
        // OnePlus opens its media output dialog via a SystemUI broadcast; the public MEDIA_OUTPUT
        // panel action does not resolve on this ROM. The receiver is exported, no permission needed.
        try {
            ctx.sendBroadcast(Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui").putExtra("package_name", playerPkg))
            log("sent LAUNCH_MEDIA_OUTPUT_DIALOG for $playerPkg")
        } catch (e: Exception) {
            log("output dialog broadcast failed ${e.message}")
            try {
                ctx.startActivity(Intent("android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("android.media.extra.PACKAGE_NAME", playerPkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) { log("panel fallback failed ${e2.message}") }
        }
    }

    // ---- Bluetooth profile-proxy helper (dedup for connect / disconnect / getConnected) ----

    private fun adapterOf(ctx: Context) =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()

    private fun withProxy(ctx: Context, adapter: BluetoothAdapter, profile: Int, action: (BluetoothProfile) -> Unit) {
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(pr: Int, proxy: BluetoothProfile) {
                try { action(proxy) } finally { adapter.closeProfileProxy(pr, proxy) }
            }
            override fun onServiceDisconnected(pr: Int) {}
        }, profile)
    }

    /** Reflected A2DP/HEADSET connect(device); Toasts once if both profiles fail. */
    fun connectA2dp(ctx: Context, mac: String) {
        val adapter = adapterOf(ctx) ?: run { toast(ctx, "No Bluetooth adapter"); return }
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) { toast(ctx, "Bad MAC $mac"); return }
        val name = try { device.name ?: mac } catch (e: SecurityException) { mac }
        profileAction(ctx, adapter, device, "connect", name, mac)
    }

    /** Reflected A2DP/HEADSET disconnect(device); best-effort, never toasts. */
    fun disconnectA2dp(ctx: Context, mac: String) {
        val adapter = adapterOf(ctx) ?: return
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) { return }
        profileAction(ctx, adapter, device, "disconnect", null, mac)
    }

    private fun profileAction(ctx: Context, adapter: BluetoothAdapter, device: BluetoothDevice,
                              method: String, toastName: String?, mac: String) {
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val pending = java.util.concurrent.atomic.AtomicInteger(profiles.size)
        val anyOk = AtomicBoolean(false)
        for (profile in profiles) withProxy(ctx, adapter, profile) { proxy ->
            try {
                val m = proxy.javaClass.getMethod(method, BluetoothDevice::class.java)
                m.isAccessible = true
                val r = m.invoke(proxy, device)
                if (r == null || r == true) anyOk.set(true)
            } catch (e: Exception) {
                val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                android.util.Log.w("LazyCar", "$method $mac", cause)
            } finally {
                if (pending.decrementAndGet() == 0 && !anyOk.get() && toastName != null)
                    toast(ctx, "Connect failed ($toastName)")
            }
        }
    }

    /** Async list of currently A2DP-connected device MACs. */
    private fun a2dpConnectedDevices(ctx: Context, done: (List<String>) -> Unit) {
        val adapter = adapterOf(ctx)
        if (adapter == null || !adapter.isEnabled) { done(emptyList()); return }
        try {
            withProxy(ctx, adapter, BluetoothProfile.A2DP) { proxy ->
                val list = try { proxy.connectedDevices.map { it.address } } catch (e: Exception) { emptyList() }
                main.post { done(list) }
            }
        } catch (e: Exception) { done(emptyList()) }
    }

    /** Undo GO: pause, disconnect speakers, optionally BT off, kill player. Returns ms to stay alive. */
    fun stop(ctx: Context): Long {
        val p = Prefs(ctx)
        setState(ctx, p, STOPPING)
        pauseMedia(ctx)
        listOf(p.mac1, p.mac2, p.mac3).filter { it.isNotEmpty() }.forEach { disconnectA2dp(ctx, it) }
        if (p.btOffOnStop) {
            val a = adapterOf(ctx)
            try { @Suppress("DEPRECATION") a?.disable() } catch (e: Exception) {}
            main.postDelayed({ if (a?.isEnabled == true) toast(ctx, "Bluetooth stays on (Android limit)") }, 1500)
        }
        try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .killBackgroundProcesses(p.playerPkg) } catch (e: Exception) {}
        main.postDelayed({ setState(ctx, p, IDLE) }, 1800)
        return 2500L
    }

    /** Best-effort real-state probe on app open: ON iff both speakers are A2DP-connected. */
    fun deriveState(ctx: Context, p: Prefs, done: (Int) -> Unit) {
        val es = effectiveState(p)
        if (es == STARTING || es == STOPPING || p.mac1.isEmpty() || p.mac2.isEmpty()) { done(es); return }
        a2dpConnectedDevices(ctx) { connected ->
            val st = if (p.mac1 in connected && p.mac2 in connected) ON else IDLE
            if (st != p.state) setState(ctx, p, st)
            done(st)
        }
    }

    private fun pauseMedia(ctx: Context) = mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
    private fun playMedia(ctx: Context) = mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun launchPlayer(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) { toast(ctx, "No player chosen"); return }
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast(ctx, "Player not found: $pkg"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i)
    }

    // phone-side Android Auto path, first installed wins
    private const val PKG_WIRELESS = "com.andrerinas.wirelesshelper"
    private const val PKG_OPENHU = "com.andrerinas.headunitrevived"
    private const val PKG_GEARHEAD = "com.google.android.projection.gearhead"

    /** Returns (package, label) of the first installed AA path, or null. */
    fun detectHeadunit(ctx: Context): Pair<String, String>? {
        val order = listOf(
            PKG_WIRELESS to "Open Headunit Wireless Helper",
            PKG_OPENHU to "Open Headunit",
            PKG_GEARHEAD to "Android Auto"
        )
        return order.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it.first) != null }
    }

    private fun launchHeadunit(ctx: Context, ip: String) {
        val target = detectHeadunit(ctx) ?: run { toast(ctx, "No head unit app installed on phone"); return }
        val pkg = target.first
        try {
            val i = if (pkg == PKG_OPENHU && ip.isNotEmpty())
                Intent(Intent.ACTION_VIEW, Uri.parse("headunit://connect?ip=$ip")).setPackage(pkg)
            else ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) { toast(ctx, "Head unit app not launchable"); return }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i)
            toast(ctx, "Started ${target.second}")
        } catch (e: android.content.ActivityNotFoundException) { toast(ctx, "Head unit app not found") }
    }

    private fun toast(ctx: Context, msg: String) =
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}
