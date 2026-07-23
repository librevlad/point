package com.point.executors

import org.junit.Assert.assertEquals
import org.junit.Test

/** EXIF orientation → upright rotation. Pure Int→Float, so it runs on the JVM. */
class BitmapsTest {

    @Test
    fun `maps exif orientation to the clockwise degrees that make it upright`() {
        assertEquals(0f, Bitmaps.rotationDegrees(1))   // ORIENTATION_NORMAL
        assertEquals(90f, Bitmaps.rotationDegrees(6))  // ROTATE_90
        assertEquals(180f, Bitmaps.rotationDegrees(3)) // ROTATE_180
        assertEquals(270f, Bitmaps.rotationDegrees(8)) // ROTATE_270
        assertEquals(0f, Bitmaps.rotationDegrees(0))   // undefined → leave as-is
    }

    @Test
    fun `sampleSize is the largest power-of-two keeping the long edge at or above target`() {
        assertEquals(1, Bitmaps.sampleSize(80, 60, 96))    // already smaller than target → no subsample
        assertEquals(2, Bitmaps.sampleSize(192, 100, 96))  // 192→96 (≥96); /4 would be 48 (<96)
        assertEquals(4, Bitmaps.sampleSize(400, 300, 96))  // 400→100 (≥96); /8 would be 50 (<96)
        assertEquals(32, Bitmaps.sampleSize(4000, 3000, 96)) // 4000→125 (≥96)
    }

    @Test
    fun `sampleSize is safe for degenerate inputs`() {
        assertEquals(1, Bitmaps.sampleSize(0, 0, 96))
        assertEquals(1, Bitmaps.sampleSize(4000, 3000, 0)) // non-positive target → no subsample
    }
}
