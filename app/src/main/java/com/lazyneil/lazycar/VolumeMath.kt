package com.lazyneil.lazycar

/** Pure volume mapping, kept separate so it can be unit-tested without an emulator. */
object VolumeMath {
    /** Map a 0..100 percentage onto an AudioManager stream's 0..streamMax index. */
    fun toStreamIndex(pct: Int, streamMax: Int): Int =
        Math.round(pct.coerceIn(0, 100) / 100f * streamMax)
}
