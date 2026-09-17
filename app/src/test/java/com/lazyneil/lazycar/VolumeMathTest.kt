package com.lazyneil.lazycar

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMathTest {
    @Test fun streamIndexRounds() {
        assertEquals(0, VolumeMath.toStreamIndex(0, 15))
        assertEquals(15, VolumeMath.toStreamIndex(100, 15))
        assertEquals(5, VolumeMath.toStreamIndex(30, 15))    // 4.5 -> 5
        assertEquals(8, VolumeMath.toStreamIndex(60, 14))    // 8.4 -> 8
    }

    @Test fun streamIndexClamps() {
        assertEquals(0, VolumeMath.toStreamIndex(-10, 16))
        assertEquals(16, VolumeMath.toStreamIndex(250, 16))
    }
}
