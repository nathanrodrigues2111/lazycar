package com.lazyneil.lazycar

/** Pure volume mapping, kept separate so it can be unit-tested without an emulator. */
object VolumeMath {
    /** Map a 0..100 percentage onto a slider's [min,max] RangeInfo span. */
    fun toRange(pct: Int, min: Float, max: Float): Float {
        val p = pct.coerceIn(0, 100)
        return min + (max - min) * p / 100f
    }

    /**
     * Screen X (or Y) for [fraction] (0..1) along a slider spanning [left]..[left+width], insetting
     * 4% at each end so the tap lands on the thumb's travel, not the rounded caps.
     */
    fun fractionToX(left: Float, width: Float, fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        val inset = width * 0.04f
        return left + inset + (width - 2 * inset) * f
    }

    /** Map a 0..100 percentage onto an AudioManager stream's 0..streamMax index. */
    fun toStreamIndex(pct: Int, streamMax: Int): Int =
        Math.round(pct.coerceIn(0, 100) / 100f * streamMax)
}
