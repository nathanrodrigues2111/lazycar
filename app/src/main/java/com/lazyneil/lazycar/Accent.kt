package com.lazyneil.lazycar

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.DynamicColors

/** Runtime accent colour. Pref stores ARGB; 0 means "System" (Material dynamic colour). */
object Accent {
    const val LIME = 0xFFC6FF3D.toInt()

    /** The 8 swatches: label to pref value (0 = System). */
    val swatches = listOf(
        "System" to 0,
        "Lime" to LIME,
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

    /** Material dynamic primary, or lime if dynamic colour is unavailable. */
    fun systemColor(ctx: Context): Int = try {
        if (DynamicColors.isDynamicColorAvailable()) {
            val themed = DynamicColors.wrapContextIfAvailable(
                ContextThemeWrapper(ctx, R.style.Theme_LazyCar))
            val ta = themed.obtainStyledAttributes(
                intArrayOf(androidx.appcompat.R.attr.colorPrimary))
            val c = ta.getColor(0, LIME); ta.recycle(); c
        } else LIME
    } catch (e: Exception) { LIME }

    /** Readable foreground for a given accent: black on light accents, white on dark. */
    fun onColor(argb: Int): Int =
        if (ColorUtils.calculateLuminance(argb) > 0.5) Color.BLACK else Color.WHITE
}
