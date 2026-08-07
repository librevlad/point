package com.point.executors

import org.junit.Assert.assertEquals
import org.opencv.core.Point
import org.junit.Test

class OpenCvScanTest {

    @Test
    fun `orderCorners sorts any input into tl, tr, br, bl`() {
        val tl = Point(10.0, 10.0)
        val tr = Point(100.0, 12.0)
        val br = Point(98.0, 90.0)
        val bl = Point(8.0, 88.0)

        val ordered = OpenCvScan.orderCorners(arrayOf(br, tl, bl, tr))

        assertEquals(tl, ordered[0])
        assertEquals(tr, ordered[1])
        assertEquals(br, ordered[2])
        assertEquals(bl, ordered[3])
    }

    @Test
    fun `distance is euclidean`() {
        assertEquals(5.0, OpenCvScan.distance(Point(0.0, 0.0), Point(3.0, 4.0)), 1e-9)
    }
}
