package com.lazyneil.lazycar

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Auto-taps the second speaker in the OnePlus output-switcher dialog so "dual audio" turns on
 * with zero user taps. GoAction opens the dialog (SystemUI broadcast) and arms us with the
 * target device name; we find that row and click it. No privileged permission needed.
 */
class TapService : AccessibilityService() {
    companion object {
        @Volatile var target: String? = null            // device name to tap, or null = idle
        @Volatile private var dumped = false
        fun arm(name: String) { target = name; dumped = false }
    }

    private val h = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val t = target ?: return
        if (e?.packageName != "com.android.systemui") return
        val root = rootInActiveWindow ?: return
        if (!dumped) { dumped = true; dump(root, 0) }

        val hit = findByText(root, t) ?: return
        val click = clickableAncestor(hit) ?: hit
        android.util.Log.e("LazyCar", "TapService tap '$t' via [${click.className}] desc='${click.contentDescription}'")
        val ok = click.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        android.util.Log.e("LazyCar", "TapService ACTION_CLICK ok=$ok")
        target = null
        h.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(applicationContext, "Dual audio on", Toast.LENGTH_LONG).show()
        }, 900)
    }

    override fun onInterrupt() {}

    private fun findByText(n: AccessibilityNodeInfo?, s: String): AccessibilityNodeInfo? {
        if (n == null) return null
        val txt = "${n.text ?: ""} ${n.contentDescription ?: ""}"
        if (txt.contains(s, true)) return n
        for (i in 0 until n.childCount) findByText(n.getChild(i), s)?.let { return it }
        return null
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = n
        while (c != null) { if (c.isClickable) return c; c = c.parent }
        return null
    }

    private fun dump(n: AccessibilityNodeInfo?, d: Int) {
        if (n == null) return
        android.util.Log.e("LazyCar", "NODE " + "  ".repeat(d) +
            "[${n.className}] text='${n.text}' desc='${n.contentDescription}' " +
            "click=${n.isClickable} id=${n.viewIdResourceName} range=${n.rangeInfo}")
        for (i in 0 until n.childCount) dump(n.getChild(i), d + 1)
    }
}
