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
    }

    private val h = Handler(Looper.getMainLooper())
    private var pollN = 0
    private var dialogSeen = false
    private var reSent = false
    private val volSet = Collections.synchronizedSet(mutableSetOf<String>())
    private val volTries = Collections.synchronizedMap(mutableMapOf<String, Int>())

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
     * Set each speaker's slider to its saved level (Prefs.volN) when "Set volume on GO" (volOnN) is
     * on and the row title matches the speaker name. Uses ACTION_SET_PROGRESS on the row's AbsSeekBar
     * (any node exposing a RangeInfo), scaling 0-100 into the slider's own [min,max]. Once per row.
     */
    private fun applyVolumes(root: AccessibilityNodeInfo, p: Prefs) {
        val targets = listOf(p.name1 to (p.volOn1 to p.vol1), p.name2 to (p.volOn2 to p.vol2))
            .filter { it.first.isNotEmpty() && it.second.first }
        if (targets.isEmpty()) return
        forEach(root) { n ->
            val range = n.rangeInfo ?: return@forEach
            val title = rowTitle(n) ?: descName(n) ?: return@forEach
            val t = targets.firstOrNull { it.first.equals(title, true) } ?: return@forEach
            if (title in volSet) return@forEach
            val pct = t.second.second.coerceIn(0, 100)
            val value = range.min + (range.max - range.min) * pct / 100f
            val b = android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
            val ok = n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, b)
            android.util.Log.e("LazyCar", "TapService set volume '$title' -> $pct% (=$value in ${range.min}..${range.max}) ok=$ok")
            if (ok) { volSet.add(title); return@forEach }
            // Retry on later polls (slider may not be ready), but give up after a few tries: a
            // sharing-group secondary member's slider rejects SET_PROGRESS on this ROM.
            val tries = (volTries[title] ?: 0) + 1; volTries[title] = tries
            if (tries >= 3) { volSet.add(title); android.util.Log.e("LazyCar", "TapService giving up volume '$title' (slider not settable)") }
        }
    }

    /** Pull "NAME" out of a "Connected to NAME." style contentDescription. */
    private fun descName(n: AccessibilityNodeInfo): String? {
        val d = n.contentDescription?.toString()?.trim() ?: return null
        val m = Regex("(?:Connected to|Playing on|In use.*?) ?(.+?)\\.?$").find(d) ?: return null
        return m.groupValues.getOrNull(1)?.trim()?.ifEmpty { null }
    }

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
