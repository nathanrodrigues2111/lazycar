package com.lazyneil.lazycar

import android.app.Activity
import android.os.Bundle

/** No-UI activity: runs the sequence then finishes. Widget starts it via PendingIntent. */
class GoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hidden debug entry: `--ez shareOnly true` runs only shareAudio (no player/play).
        // MACs come from prefs; if a pref is empty, fall back to `--es mac1/--es mac2` extras.
        if (intent?.getBooleanExtra("shareOnly", false) == true) {
            val p = Prefs(applicationContext)
            val m1 = p.mac1.ifEmpty { intent.getStringExtra("mac1").orEmpty() }
            val m2 = p.mac2.ifEmpty { intent.getStringExtra("mac2").orEmpty() }
            android.util.Log.e("LazyCar", "GoActivity shareOnly mac1=$m1 mac2=$m2")
            GoAction.shareAudio(applicationContext, m1, m2, p.playerPkg)
        } else {
            GoAction.run(applicationContext)
        }
        finish()
    }
}
