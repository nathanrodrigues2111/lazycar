package com.lazyneil.lazycar

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Collections

/**
 * Zero-tap dual audio. Two jobs:
 *  - buttons: click a system confirm button (the BT "Allow"/"Turn on" enable dialog). Event-driven.
 *  - adds: in the OnePlus output switcher, tick every "Add device to group." checkbox so all
 *    connected speakers join the sharing group, then dismiss. POLLING, not event-driven: the OnePlus
 *    dialog throttles/mislabels its accessibility events, so instead of waiting for the right event
 *    we scan getWindows() every 400ms while armed and click what we find. No privileged permission.
 */
class TapService : AccessibilityService() {
    class Tile(val labels: List<String>, val wantOn: Boolean, val snapKey: String, val feature: String)

    companion object {
        @Volatile private var instance: TapService? = null

        // ---- add-to-group mode (output switcher) ----
        @Volatile var armedUntil: Long = 0L
        @Volatile var ownsPanel: Boolean = false     // true only when LazyCar opened the panel
        @Volatile var finishing: Boolean = false
        private val clicked = Collections.synchronizedSet(mutableSetOf<String>())
        fun arm(windowMs: Long = 8500L) {
            armedUntil = SystemClock.uptimeMillis() + windowMs
            ownsPanel = true; finishing = false; clicked.clear()
            instance?.startAddsPoll()
        }
        fun disarm() { armedUntil = 0L; ownsPanel = false }

        // ---- button mode (BT enable dialog) ----
        @Volatile var buttonTexts: List<String> = emptyList()
        @Volatile var buttonArmedUntil: Long = 0L
        @Volatile private var buttonDumped = false
        fun armButtons(texts: List<String>, windowMs: Long = 12000L) {
            buttonTexts = texts; buttonArmedUntil = SystemClock.uptimeMillis() + windowMs; buttonDumped = false
        }
        fun disarmButtons() { buttonTexts = emptyList(); buttonArmedUntil = 0L }

        // ---- quick-settings tile mode (mobile data / hotspot), all in one QS session ----
        @Volatile var tiles: List<Tile> = emptyList()
        @Volatile var tileArmedUntil: Long = 0L
        /** Open Quick Settings once and drive every [tiles] entry to its wanted state, recording each
         *  tile's pre-state into Prefs[snapKey] (blank key = don't record). */
        fun armTiles(t: List<Tile>, windowMs: Long = 9000L) {
            tiles = t; tileArmedUntil = SystemClock.uptimeMillis() + windowMs
            instance?.startTilePoll()
        }
        fun disarmTiles() { tileArmedUntil = 0L; tiles = emptyList() }
    }

    private val h = Handler(Looper.getMainLooper())
    private var pollN = 0
    private var dialogSeen = false
    private var reSent = false
    private val volSet = Collections.synchronizedSet(mutableSetOf<String>())
    private val volTries = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private var tileOpened = false
    private var tileFinished = false
    private val tileDone = Collections.synchronizedSet(mutableSetOf<String>())

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        handleButtons(e)   // adds are handled by the poll loop, not events
    }

    /** Click a system confirm button (BT enable dialog etc.) once, then disarm. */
    private fun handleButtons(e: AccessibilityEvent?) {
        if (buttonTexts.isEmpty()) return
        if (SystemClock.uptimeMillis() >= buttonArmedUntil) { buttonTexts = emptyList(); return }
        val pkg = e?.packageName?.toString() ?: return
        if (!(pkg == "com.android.settings" || pkg == "com.android.systemui" ||
              pkg.startsWith("com.oplus") || pkg == "com.android.permissioncontroller")) return
        val root = rootInActiveWindow ?: return
        if (!buttonDumped) { buttonDumped = true; android.util.Log.e("LazyCar", "BUTTON DIALOG pkg=$pkg"); dump(root, 0) }
        val btn = find(root) { n ->
            n.isClickable && n.text?.toString()?.trim()?.let { t -> buttonTexts.any { t.equals(it, true) || t.contains(it, true) } } == true
        } ?: return
        val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        android.util.Log.e("LazyCar", "TapService clicked confirm '${btn.text}' ok=$ok")
        disarmButtons()
    }

    // ---- polling add-to-group ----

    fun startAddsPoll() {
        pollN = 0; dialogSeen = false; reSent = false; volSet.clear(); volTries.clear()
        h.removeCallbacks(pollRunnable)
        h.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (armedUntil == 0L || finishing) return
            if (SystemClock.uptimeMillis() >= armedUntil) { finishPoll(); return }
            pollN++
            val prefs = Prefs(applicationContext)
            var winCount = 0; var adds = 0; var removes = 0; var sawDialog = false
            for (w in (windows ?: emptyList())) {
                val root = w.root ?: continue
                winCount++
                if (root.packageName != "com.android.systemui") continue
                applyVolumes(root, prefs)
                forEach(root) { n ->
                    if (n.viewIdResourceName?.endsWith("/check_box_area") != true) return@forEach
                    val d = n.contentDescription?.toString()?.trim() ?: return@forEach
                    if (d.equals("Add device to group.", true)) {
                        sawDialog = true; adds++
                        val title = rowTitle(n) ?: n.hashCode().toString()
                        if (title !in clicked) {
                            val target = if (n.isClickable) n else clickableAncestor(n) ?: n
                            val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            clicked.add(title)
                            android.util.Log.e("LazyCar", "TapService poll#$pollN click add '$title' ok=$ok")
                        }
                    } else if (d.equals("Remove device from group.", true)) { sawDialog = true; removes++ }
                }
            }
            if (sawDialog) dialogSeen = true
            android.util.Log.e("LazyCar", "TapService poll#$pollN windows=$winCount add=$adds remove=$removes clicked=${clicked.size}")

            // Dialog never showed up -> re-open it once (~1.6s in).
            if (!dialogSeen && pollN >= 4 && !reSent) { reSent = true; reopenSwitcher() }

            // Done when everything connected is grouped (no Add rows, >=2 Remove) AND every
            // "set volume on GO" slider has been applied. Window timeout is the fallback.
            val volDone = listOf(prefs.name1 to prefs.volOn1, prefs.name2 to prefs.volOn2)
                .none { it.first.isNotEmpty() && it.second && it.first !in volSet }
            if (dialogSeen && adds == 0 && removes >= 2 && volDone) { finishPoll(); return }
            h.postDelayed(this, 400)
        }
    }

    private fun finishPoll() {
        if (finishing) return
        finishing = true
        Prefs(applicationContext).grouped = clicked.isNotEmpty() || dialogSeen
        android.util.Log.e("LazyCar", "TapService poll done (clicked=${clicked.size}, grouped=${Prefs(applicationContext).grouped})")
        val owned = ownsPanel
        disarm()
        h.postDelayed({
            if (owned) performGlobalAction(GLOBAL_ACTION_BACK)
            if (clicked.isNotEmpty()) Toast.makeText(applicationContext, "Dual audio on", Toast.LENGTH_LONG).show()
        }, 800)
    }

    /**
     * Set each speaker's slider to its saved level (Prefs.volN) when "Set volume on GO" (volOnN) is on.
     * Matches a slider by row title (falls back to positional order: name1 -> first volume_seekbar,
     * name2 -> second). Scales 0-100 into the slider's own [min,max] via VolumeMath, verifies the
     * value stuck (re-read), retries once, then falls back to AudioManager on the active stream.
     * UNTESTED on device (phone unavailable); the OnePlus slider is known to be finicky.
     */
    private fun applyVolumes(root: AccessibilityNodeInfo, p: Prefs) {
        val targets = listOf(Triple(p.name1, p.volOn1, p.vol1), Triple(p.name2, p.volOn2, p.vol2))
            .filter { it.first.isNotEmpty() && it.second }
        if (targets.isEmpty()) return
        val sliders = mutableListOf<AccessibilityNodeInfo>()
        forEach(root) { if (it.rangeInfo != null) sliders.add(it) }
        if (sliders.isEmpty()) return
        for ((idx, t) in targets.withIndex()) {
            val name = t.first; if (name in volSet) continue
            val pct = t.third.coerceIn(0, 100)
            val node = sliders.firstOrNull { (rowTitle(it) ?: descName(it))?.equals(name, true) == true }
                ?: sliders.getOrNull(idx) ?: continue
            val range = node.rangeInfo ?: continue
            if (!node.isEnabled) {   // disabled in a sharing group -> go straight to the stream fallback
                android.util.Log.e("LazyCar", "volume '$name' slider disabled; stream fallback")
                audioFallback(pct, name); volSet.add(name); continue
            }
            val value = VolumeMath.toRange(pct, range.min, range.max)
            val ok = setProgress(node, value)
            android.util.Log.e("LazyCar", "volume '$name' -> $value ($pct%, ${range.min}..${range.max}, before=${range.current}) ok=$ok")
            volSet.add(name)
            if (ok) h.postDelayed({ verifyVolume(name, pct, value) }, 300)
            else audioFallback(pct, name)
        }
    }

    private fun setProgress(n: AccessibilityNodeInfo, value: Float): Boolean {
        val b = android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, b)
    }

    /** Re-read the slider; if it didn't move, nudge once by +-1, else fall back to the stream volume. */
    private fun verifyVolume(name: String, pct: Int, value: Float) {
        var node: AccessibilityNodeInfo? = null
        for (w in (windows ?: emptyList())) {
            val root = w.root ?: continue
            if (root.packageName != "com.android.systemui") continue
            node = find(root) { it.rangeInfo != null && (rowTitle(it) ?: descName(it))?.equals(name, true) == true }
            if (node != null) break
        }
        val range = node?.rangeInfo
        val cur = range?.current
        if (range != null && cur != null && kotlin.math.abs(cur - value) <= (range.max - range.min) * 0.03f) {
            android.util.Log.e("LazyCar", "volume '$name' verify ok current=$cur"); return
        }
        val tries = volTries[name] ?: 0
        if (node != null && range != null && tries < 1) {   // one nudge retry
            volTries[name] = tries + 1
            val nudged = if (value < range.max) value + 1f else value - 1f
            val ok = setProgress(node, nudged)
            android.util.Log.e("LazyCar", "volume '$name' retry -> $nudged ok=$ok (was current=$cur)")
            h.postDelayed({ verifyVolume(name, pct, value) }, 300); return
        }
        android.util.Log.e("LazyCar", "volume '$name' did not stick (current=$cur target=$value); stream fallback")
        audioFallback(pct, name)
    }

    /** Absolute volume on the active A2DP sink via AudioManager (per-device sliders can fail on OnePlus). */
    private fun audioFallback(pct: Int, tag: String) {
        try {
            val am = applicationContext.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val idx = VolumeMath.toStreamIndex(pct, max)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, idx, 0)
            android.util.Log.e("LazyCar", "volume fallback '$tag' -> $idx/$max (STREAM_MUSIC)")
        } catch (e: Exception) { android.util.Log.e("LazyCar", "volume fallback '$tag' failed ${e.message}") }
    }

    /** Pull "NAME" out of a "Connected to NAME." style contentDescription. */
    private fun descName(n: AccessibilityNodeInfo): String? {
        val d = n.contentDescription?.toString()?.trim() ?: return null
        val m = Regex("(?:Connected to|Playing on|In use.*?) ?(.+?)\\.?$").find(d) ?: return null
        return m.groupValues.getOrNull(1)?.trim()?.ifEmpty { null }
    }

    // ---- quick-settings tile toggle (mobile data / hotspot) ----

    fun startTilePoll() {
        tileOpened = false; tileFinished = false; tileDone.clear()
        h.removeCallbacks(tileRunnable); h.post(tileRunnable)
    }

    private val tileRunnable = object : Runnable {
        override fun run() {
            if (tileArmedUntil == 0L || tileFinished) return
            if (SystemClock.uptimeMillis() >= tileArmedUntil) {
                (tiles.map { it.feature } - tileDone).forEach { android.util.Log.e("LazyCar", "tile '$it' not found (timeout)") }
                finishTile(); return
            }
            if (!tileOpened) { tileOpened = true; performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
            val roots = (windows ?: emptyList()).mapNotNull { it.root }.filter { it.packageName == "com.android.systemui" }
            for (t in tiles) {
                if (t.feature in tileDone) continue
                var node: AccessibilityNodeInfo? = null
                for (root in roots) { node = find(root) { n ->
                    n.isCheckable && n.contentDescription?.toString()?.let { d -> t.labels.any { d.contains(it, true) } } == true
                }; if (node != null) break }
                val n = node ?: continue
                val on = n.isChecked
                when (t.snapKey) {                        // record pre-toggle state for STOP to restore
                    "snapData" -> Prefs(applicationContext).snapDataWasOn = on
                    "snapHotspot" -> Prefs(applicationContext).snapHotspotWasOn = on
                }
                if (on != t.wantOn) {
                    val target = if (n.isClickable) n else clickableAncestor(n) ?: n
                    val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    android.util.Log.e("LazyCar", "tile '${t.feature}' was ${onoff(on)} -> ${onoff(t.wantOn)} ok=$ok")
                } else android.util.Log.e("LazyCar", "tile '${t.feature}' already ${onoff(on)} -> left")
                tileDone.add(t.feature)
            }
            if (tiles.all { it.feature in tileDone }) { finishTile(); return }
            h.postDelayed(this, 400)
        }
    }

    private fun finishTile() {
        if (tileFinished) return
        tileFinished = true; disarmTiles()
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }, 500)
    }

    private fun onoff(b: Boolean) = if (b) "on" else "off"

    private fun reopenSwitcher() {
        val pkg = Prefs(applicationContext).playerPkg
        android.util.Log.e("LazyCar", "TapService re-sending output dialog for $pkg")
        try {
            sendBroadcast(Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui").putExtra("package_name", pkg))
        } catch (e: Exception) { android.util.Log.e("LazyCar", "re-send failed ${e.message}") }
    }

    /** Title text of the device row (id .../main_content) that contains node [n]. */
    private fun rowTitle(n: AccessibilityNodeInfo): String? {
        var row: AccessibilityNodeInfo? = n
        while (row != null && row.viewIdResourceName?.endsWith("/main_content") != true) row = row.parent
        val r = row ?: return null
        return find(r) { it.viewIdResourceName?.endsWith("/title") == true }?.text?.toString()
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = n.parent
        while (p != null) { if (p.isClickable) return p; p = p.parent }
        return null
    }

    private fun forEach(n: AccessibilityNodeInfo?, action: (AccessibilityNodeInfo) -> Unit) {
        if (n == null) return
        action(n)
        for (i in 0 until n.childCount) forEach(n.getChild(i), action)
    }

    private fun find(n: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (pred(n)) return n
        for (i in 0 until n.childCount) find(n.getChild(i), pred)?.let { return it }
        return null
    }

    private fun dump(n: AccessibilityNodeInfo?, d: Int) {
        if (n == null) return
        android.util.Log.e("LazyCar", "NODE " + "  ".repeat(d) +
            "[${n.className}] text='${n.text}' desc='${n.contentDescription}' click=${n.isClickable} id=${n.viewIdResourceName}")
        for (i in 0 until n.childCount) dump(n.getChild(i), d + 1)
    }
}
