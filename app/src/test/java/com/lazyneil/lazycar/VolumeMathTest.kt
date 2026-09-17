package com.lazyneil.lazycar

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMathTest {
    @Test fun rangeEndpointsAndMidpoint() {
        assertEquals(0f, VolumeMath.toRange(0, 0f, 160000f), 0.001f)
        assertEquals(160000f, VolumeMath.toRange(100, 0f, 160000f), 0.001f)
        assertEquals(48000f, VolumeMath.toRange(30, 0f, 160000f), 0.001f)
        assertEquals(15f, VolumeMath.toRange(50, 10f, 20f), 0.001f)   // non-zero min
    }

    @Test fun rangeClampsOutOfBounds() {
        assertEquals(0f, VolumeMath.toRange(-10, 0f, 100f), 0.001f)
        assertEquals(100f, VolumeMath.toRange(250, 0f, 100f), 0.001f)
    }

    @Test fun fractionToXInsetsBothEnds() {
        // left=100, width=1000 (spans 100..1100) -> 4% inset = 40px, usable travel 140..1060
        assertEquals(140f, VolumeMath.fractionToX(100f, 1000f, 0f), 0.001f)
        assertEquals(1060f, VolumeMath.fractionToX(100f, 1000f, 1f), 0.001f)
        assertEquals(600f, VolumeMath.fractionToX(100f, 1000f, 0.5f), 0.001f)
    }

    @Test fun fractionToXClampsOutOfRange() {
        assertEquals(140f, VolumeMath.fractionToX(100f, 1000f, -0.5f), 0.001f)
        assertEquals(1060f, VolumeMath.fractionToX(100f, 1000f, 2f), 0.001f)
    }

    @Test fun streamIndexRounds() {
        assertEquals(0, VolumeMath.toStreamIndex(0, 15))
        assertEquals(15, VolumeMath.toStreamIndex(100, 15))
        assertEquals(5, VolumeMath.toStreamIndex(30, 15))    // 4.5 -> 5
        assertEquals(8, VolumeMath.toStreamIndex(60, 14))    // 8.4 -> 8
    }
}
