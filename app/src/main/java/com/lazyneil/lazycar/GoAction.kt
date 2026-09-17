package com.lazyneil.lazycar

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast

/**
 * The whole "get in car" sequence. Called from GO button and the widget's GoActivity.
 * All BT connect is best-effort via reflection; failures Toast, never crash.
 */
object GoAction {
    private val main = Handler(Looper.getMainLooper())

    fun run(ctx: Context) {
        val p = Prefs(ctx)
        if (p.mac1.isNotEmpty()) connectA2dp(ctx, p.mac1)
        if (p.mac2.isNotEmpty()) main.postDelayed({ connectA2dp(ctx, p.mac2) }, 400)
        // tablet: BT link wakes Open Headunit auto-start on the tablet
        if (p.headunit && p.mac3.isNotEmpty()) main.postDelayed({ connectA2dp(ctx, p.mac3) }, 800)

        // connects -> player launch -> shareAudio -> play
        main.postDelayed({ launchPlayer(ctx, p.playerPkg) }, 2000)
        if (p.dualAudio && p.mac1.isNotEmpty() && p.mac2.isNotEmpty())
            main.postDelayed({ shareAudio(ctx, p.mac1, p.mac2, p.playerPkg) }, 3000)
        main.postDelayed({ playMedia(ctx) }, 4500)

        if (p.headunit) main.postDelayed({ launchHeadunit(ctx, p.headunitIp) }, 5000)
    }

    /**
     * Best-effort A2DP "dual audio" via public MediaRouter2 (OnePlus startSharing needs
     * BLUETOOTH_PRIVILEGED, which a side-loaded app can't hold — see reverse/SHARING_API.md).
     * If both speakers don't end up selected, fall back to the system output switcher.
     * ponytail: MediaRouter2 group-select is unconfirmed on this OEM; switcher is the reliable path.
     */
    fun shareAudio(ctx: Context, mac1: String, mac2: String, playerPkg: String) {
        // Primary no-privilege path: open the OnePlus output switcher and let TapService (an
        // AccessibilityService the user enables) tap the second speaker to turn on dual audio.
        // MediaRouter2 can't do it (app only sees DEFAULT_ROUTE) and the OnePlus startSharing API
        // needs a signature permission (see reverse/SHARING_API.md), so this is the reliable path.
        try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val n2 = adapter?.let { try { it.getRemoteDevice(mac2).name } catch (e: Exception) { null } } ?: mac2
            TapService.arm(n2)
            android.util.Log.e("LazyCar", "shareAudio: armed target='$n2', opening output switcher")
            openOutputSwitcher(ctx, playerPkg)
            // Give up after 6s if the service never tapped (not enabled, or row not found).
            main.postDelayed({
                if (TapService.target != null) {
                    TapService.target = null
                    android.util.Log.e("LazyCar", "shareAudio: auto-tap timeout; leaving switcher open")
                    toast(ctx, "Tap second speaker to share")
                }
            }, 6000)
        } catch (e: Exception) {
            android.util.Log.e("LazyCar", "shareAudio failed", e)
            openOutputSwitcher(ctx, playerPkg)
        }
    }

    private fun openOutputSwitcher(ctx: Context, playerPkg: String) {
        // OnePlus opens its media output dialog via a SystemUI broadcast; the public
        // MEDIA_OUTPUT panel action does not resolve on this ROM. Receiver is exported, no perm.
        try {
            ctx.sendBroadcast(Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui")
                .putExtra("package_name", playerPkg))
            android.util.Log.e("LazyCar", "sent LAUNCH_MEDIA_OUTPUT_DIALOG for $playerPkg")
        } catch (e: Exception) {
            android.util.Log.e("LazyCar", "output dialog broadcast failed; trying panel", e)
            try {
                ctx.startActivity(Intent("android.settings.panel.action.MEDIA_OUTPUT")
                    .putExtra("android.media.extra.PACKAGE_NAME", playerPkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) { android.util.Log.e("LazyCar", "panel fallback failed", e2) }
        }
    }

    /**
     * Connect an audio device by MAC via the A2DP profile proxy and reflected connect().
     * Swap this out later if the research worker finds a better path.
     * ponytail: reflection on a hidden API — may be blocked on some OEM/AOSP builds.
     */
    fun connectA2dp(ctx: Context, mac: String) {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { toast(ctx, "No Bluetooth adapter"); return }
        val device: BluetoothDevice = try { adapter.getRemoteDevice(mac) }
        catch (e: Exception) { toast(ctx, "Bad MAC $mac"); return }

        val name = try { device.name ?: mac } catch (e: SecurityException) { mac }
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        val pending = java.util.concurrent.atomic.AtomicInteger(profiles.size)
        val anyOk = java.util.concurrent.atomic.AtomicBoolean(false)
        val lastErr = java.util.concurrent.atomic.AtomicReference("no profile proxy")

        for (profile in profiles) {
            adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        m.isAccessible = true
                        val r = m.invoke(proxy, device)
                        if (r == null || r == true) anyOk.set(true)
                        else lastErr.set("connect() returned false")
                    } catch (e: Exception) {
                        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                        android.util.Log.w("LazyCar", "connect $p $mac", cause)
                        lastErr.set("${cause.javaClass.simpleName}: ${cause.message}")
                    } finally {
                        adapter.closeProfileProxy(p, proxy)
                        // only complain once both A2DP and HEADSET have failed
                        if (pending.decrementAndGet() == 0 && !anyOk.get())
                            toast(ctx, "Connect failed ($name): ${lastErr.get()}")
                    }
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
    }

    private fun launchPlayer(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) { toast(ctx, "No player chosen"); return }
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) { toast(ctx, "Player not found: $pkg"); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun playMedia(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = android.os.SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
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
        val target = detectHeadunit(ctx)
        if (target == null) { toast(ctx, "No head unit app installed on phone"); return }
        val pkg = target.first
        try {
            val i = if (pkg == PKG_OPENHU && ip.isNotEmpty())
                Intent(Intent.ACTION_VIEW, Uri.parse("headunit://connect?ip=$ip")).setPackage(pkg)
            else ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) { toast(ctx, "Head unit app not launchable"); return }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            toast(ctx, "Started ${target.second}")
        } catch (e: android.content.ActivityNotFoundException) {
            toast(ctx, "Head unit app not found")
        }
    }

    private fun toast(ctx: Context, msg: String) =
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}
