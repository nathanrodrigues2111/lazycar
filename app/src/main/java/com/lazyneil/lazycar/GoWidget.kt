package com.lazyneil.lazycar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

class GoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val p = Prefs(ctx)
        val st = GoAction.effectiveState(p)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, GoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Lime pill with a black car glyph; the pill turns red once the sequence is ON.
        val bg = if (st == GoAction.ON || st == GoAction.STOPPING) R.drawable.widget_bg_on
                 else R.drawable.widget_bg
        val desc = when (st) {
            GoAction.ON -> "LazyCar STOP"; GoAction.STARTING -> "LazyCar starting"
            GoAction.STOPPING -> "LazyCar stopping"; else -> "LazyCar GO"
        }
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_go)
            v.setInt(R.id.widget_button, "setBackgroundResource", bg)
            v.setImageViewResource(R.id.widget_button, R.drawable.ic_car)
            v.setInt(R.id.widget_button, "setColorFilter", Color.BLACK)
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
