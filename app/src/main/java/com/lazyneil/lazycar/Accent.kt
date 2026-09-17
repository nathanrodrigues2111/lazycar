package com.lazyneil.lazycar

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.DynamicColors

/** Runtime accent colour. Pref stores ARGB; 0 means "System" (Material dynamic colour). */
object Accent {

    /** The 8 swatches: label to pref value (0 = System). Lime comes from @color/accent. */
    fun swatches(ctx: Context) = listOf(
        "System" to 0,
        "Lime" to ContextCompat.getColor(ctx, R.color.accent),
        "Electric blue" to 0xFF4DA3FF.toInt(),
        "OnePlus red" to 0xFFEB0028.toInt(),
        "Orange" to 0xFFFF8A3D.toInt(),
        "Purple" to 0xFFB388FF.toInt(),
        "Cyan" to 0xFF00E5FF.toInt(),
        "White" to 0xFFFFFFFF.toInt()
    )

    fun color(ctx: Context): Int {
        val v = Prefs(ctx).accent
        return if (v != 0) v else systemColor(ctx)
    }

    /** Make colorPrimary follow the chosen accent for this activity's whole theme, so Material
     *  widgets (dialogs, text fields, sliders, switches...) render in it. Call before setContentView. */
    fun apply(act: Activity) {
        val v = Prefs(act).accent
        if (v == 0) DynamicColors.applyToActivityIfAvailable(act)   // System (lime stays if unavailable)
        else act.theme.applyStyle(styleFor(v), true)
    }

    private fun styleFor(v: Int) = when (v) {
        0xFF4DA3FF.toInt() -> R.style.AccentOverlay_Blue
        0xFFEB0028.toInt() -> R.style.AccentOverlay_Red
        0xFFFF8A3D.toInt() -> R.style.AccentOverlay_Orange
        0xFFB388FF.toInt() -> R.style.AccentOverlay_Purple
        0xFF00E5FF.toInt() -> R.style.AccentOverlay_Cyan
        0xFFFFFFFF.toInt() -> R.style.AccentOverlay_White
        else -> R.style.AccentOverlay_Lime
    }

    /** Material dynamic primary, or lime if dynamic colour is unavailable. */
    fun systemColor(ctx: Context): Int {
        val lime = ContextCompat.getColor(ctx, R.color.accent)
        return try {
            if (DynamicColors.isDynamicColorAvailable()) {
                val themed = DynamicColors.wrapContextIfAvailable(
                    ContextThemeWrapper(ctx, R.style.Theme_LazyCar))
                val ta = themed.obtainStyledAttributes(
                    intArrayOf(androidx.appcompat.R.attr.colorPrimary))
                val c = ta.getColor(0, lime); ta.recycle(); c
            } else lime
        } catch (e: Exception) { lime }
    }

    /** Readable foreground for a given accent: black on all but very dark accents (threshold 0.18,
     *  so mid-tone accents like the electric-blue pill get a black glyph/text, not white). */
    fun onColor(argb: Int): Int =
        if (ColorUtils.calculateLuminance(argb) > 0.18) Color.BLACK else Color.WHITE
}
