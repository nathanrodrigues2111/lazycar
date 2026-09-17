package com.lazyneil.lazycar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Short-lived foreground service that owns the GO/STOP sequence. Running as a foreground service
 * keeps the process (and GoAction's postDelayed chain + BT-enable receiver) alive independently of
 * any activity - GoActivity finishes immediately, so the system-enable dialog can take focus.
 */
class GoService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        val dur = when (intent?.getStringExtra("action")) {
            "stop" -> GoAction.stop(applicationContext)
            "share" -> {
                val m1 = intent.getStringExtra("mac1").orEmpty()
                val m2 = intent.getStringExtra("mac2").orEmpty()
                val pkg = intent.getStringExtra("player").orEmpty()
                GoAction.shareAudio(applicationContext, m1, m2, pkg); 9000L
            }
            "volume" -> { GoAction.applyVolumesNow(applicationContext); 11000L }
            else -> GoAction.run(applicationContext)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }, dur + 500L)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val chId = "lazycar_go"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(chId) == null)
                nm.createNotificationChannel(NotificationChannel(chId, "Car setup", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, chId)
            .setContentTitle("LazyCar")
            .setContentText("Setting up for the car…")
            .setSmallIcon(R.drawable.ic_car_glyph)
            .build()
    }
}
