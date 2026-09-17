package com.lazyneil.lazycar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class GoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, GoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val bg = if (Prefs(ctx).amoled) R.drawable.widget_bg_black else R.drawable.widget_bg
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_go)
            v.setInt(R.id.widget_button, "setBackgroundResource", bg)
            v.setOnClickPendingIntent(R.id.widget_button, pi)
            mgr.updateAppWidget(id, v)
        }
    }
}
