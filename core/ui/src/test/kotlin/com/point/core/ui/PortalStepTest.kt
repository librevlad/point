package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalStepTest {

    @Test
    fun `advances one step every few seconds and clamps to the last`() {
        assertEquals(0, portalStep(0, 3))
        assertEquals(0, portalStep(3, 3))
        assertEquals(1, portalStep(4, 3))
        assertEquals(2, portalStep(8, 3))
        assertEquals(2, portalStep(999, 3))
    }

    @Test
    fun `is monotonic and always a valid index`() {
        var prev = 0
        (0..60).forEach { t ->
            val s = portalStep(t, 4)
            assertTrue("in range at $t", s in 0..3)
            assertTrue("monotonic at $t", s >= prev)
            prev = s
        }
    }

    @Test
    fun `degenerate step counts stay at zero`() {
        assertEquals(0, portalStep(20, 1))
        assertEquals(0, portalStep(20, 0))
    }
}
