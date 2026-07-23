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
}
