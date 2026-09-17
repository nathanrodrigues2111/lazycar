package com.lazyneil.lazycar

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Collections

/**
 * Zero-tap dual audio. Two independent jobs, each active only inside a short armed window:
 *  - buttons: click a system confirm button (the BT "Allow"/"Turn on" enable dialog).
 *  - adds: in the OnePlus output switcher, click every "Add device to group." checkbox so all
 *    connected speakers join the sharing group, then dismiss. No device-name matching.
 * No privileged permission is needed.
 */
class TapService : AccessibilityService() {
    companion object {
        // ---- add-to-group mode (output switcher) ----
        @Volatile var armedUntil: Long = 0L
        @Volatile var ownsPanel: Boolean = false     // true only when LazyCar opened the panel
        @Volatile var finishing: Boolean = false
        private val clicked = Collections.synchronizedSet(mutableSetOf<String>())
        @Volatile private var dumped = false
        fun arm(windowMs: Long = 10000L) {
            armedUntil = SystemClock.uptimeMillis() + windowMs
            ownsPanel = true; finishing = false; dumped = false; clicked.clear()
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

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        handleButtons(e)
        handleAdds(e)
    }

    override fun onInterrupt() {}

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

    /** In the output switcher, click each "Add device to group." checkbox once, then dismiss. */
    private fun handleAdds(e: AccessibilityEvent?) {
        if (armedUntil == 0L || finishing) return
        if (SystemClock.uptimeMillis() >= armedUntil) { disarm(); return }
        if (e?.packageName != "com.android.systemui") return
        val root = rootInActiveWindow ?: return
        if (!dumped) { dumped = true; dump(root, 0) }

        val add = find(root) { n ->
            n.isClickable && n.viewIdResourceName?.endsWith("/check_box_area") == true &&
                n.contentDescription?.toString()?.trim().equals("Add device to group.", true) &&
                (rowTitle(n)?.let { it !in clicked } ?: true)
        }
        if (add != null) {
            val title = rowTitle(add) ?: add.hashCode().toString()
            val ok = add.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clicked.add(title)
            android.util.Log.e("LazyCar", "TapService added group row '$title' ok=$ok")
            return                                   // panel re-renders; next event handles the rest
        }
        // No more addable rows -> done. Dismiss only if we opened the panel.
        finishing = true
        android.util.Log.e("LazyCar", "TapService: no more 'Add device to group' rows (added ${clicked.size})")
        val owned = ownsPanel
        disarm()
        h.postDelayed({
            if (owned) performGlobalAction(GLOBAL_ACTION_BACK)
            if (clicked.isNotEmpty()) Toast.makeText(applicationContext, "Dual audio on", Toast.LENGTH_LONG).show()
        }, 800)
    }

    /** Title text of the device row (id .../main_content) that contains node [n]. */
    private fun rowTitle(n: AccessibilityNodeInfo): String? {
        var row: AccessibilityNodeInfo? = n
        while (row != null && row.viewIdResourceName?.endsWith("/main_content") != true) row = row.parent
        val r = row ?: return null
        return find(r) { it.viewIdResourceName?.endsWith("/title") == true }?.text?.toString()
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
