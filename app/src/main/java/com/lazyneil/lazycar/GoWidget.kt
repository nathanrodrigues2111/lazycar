package com.lazyneil.lazycar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews

class GoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val p = Prefs(ctx)
        val st = GoAction.effectiveState(p)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, GoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Black glyph on a state-coloured pill: accent when idle, red when ON, grey while busy
        // (a spinner replaces the glyph while STARTING / STOPPING).
        val busy = st == GoAction.STARTING || st == GoAction.STOPPING
        val pillColor = when {
            busy -> 0xFF22252B.toInt()
            st == GoAction.ON -> 0xFFFF5252.toInt()
            else -> Accent.color(ctx)
        }
        val pill = android.content.res.ColorStateList.valueOf(pillColor)
        val glyph = if (st == GoAction.ON) R.drawable.ic_stop_white else R.drawable.ic_car_glyph
        val desc = when (st) {
            GoAction.ON -> "LazyCar STOP"; GoAction.STARTING -> "LazyCar starting"
            GoAction.STOPPING -> "LazyCar stopping"; else -> "LazyCar GO"
        }
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_go)
            v.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_bg)
            v.setColorStateList(R.id.widget_button, "setBackgroundTintList", pill)
            v.setImageViewResource(R.id.widget_glyph, glyph)
            v.setInt(R.id.widget_glyph, "setColorFilter", Color.BLACK)
            v.setViewVisibility(R.id.widget_glyph, if (busy) View.INVISIBLE else View.VISIBLE)
            v.setViewVisibility(R.id.widget_progress, if (busy) View.VISIBLE else View.GONE)
            v.setContentDescription(R.id.widget_button, desc)
            v.setOnClickPendingIntent(R.id.widget_button, pi)
            mgr.updateAppWidget(id, v)
        }
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, GoWidget::class.java))
            if (ids.isNotEmpty()) GoWidget().onUpdate(ctx, mgr, ids)
        }
    }
}
