package com.point.core.ui

import androidx.compose.ui.geometry.Offset
import com.point.core.model.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #115 drag-to-connect: the object is dragged towards a bubble and the nearest one
 * inside the magnet radius becomes the connection candidate. Pure geometry — the
 * gesture code just feeds positions in.
 */
class MagnetTest {

    private val scan = CapabilityId("scan")
    private val ocr = CapabilityId("ocr")

    @Test
    fun `no bubbles - no target`() {
        assertNull(magnetTarget(Offset(10f, 10f), emptyMap(), radiusPx = 100f))
    }

    @Test
    fun `bubble inside radius is the target`() {
        val centers = mapOf(scan to Offset(30f, 40f)) // 50px away
        assertEquals(scan, magnetTarget(Offset.Zero, centers, radiusPx = 60f))
    }

    @Test
    fun `bubble outside radius is not a target`() {
        val centers = mapOf(scan to Offset(30f, 40f)) // 50px away
        assertNull(magnetTarget(Offset.Zero, centers, radiusPx = 49f))
    }

    @Test
    fun `the nearest of several candidates wins`() {
        val centers = mapOf(
            ocr to Offset(0f, 80f),  // 80px away
            scan to Offset(30f, 40f), // 50px away
        )
        assertEquals(scan, magnetTarget(Offset.Zero, centers, radiusPx = 100f))
    }

    @Test
    fun `exactly on the radius still connects`() {
        val centers = mapOf(scan to Offset(0f, 100f))
        assertEquals(scan, magnetTarget(Offset.Zero, centers, radiusPx = 100f))
    }
}
