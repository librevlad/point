package com.point.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuedLinkTest {

    private val issued = mapOf(
        "entity.url" to "https://relay.example/d/abc",
        "drop.expires" to "сутки",
    )

    @Test
    fun `a link Point has just issued is shown as a code`() {
        assertEquals("https://relay.example/d/abc", issuedLinkOf(issued))
    }

    @Test
    fun `someone else's url is not shown as a code`() {
        assertNull(issuedLinkOf(mapOf("entity.url" to "https://example.org/article")))
    }

    @Test
    fun `nothing is shown without a url`() {
        assertNull(issuedLinkOf(mapOf("drop.expires" to "сутки")))
        assertNull(issuedLinkOf(mapOf("entity.url" to "  ", "drop.expires" to "сутки")))
        assertNull(issuedLinkOf(emptyMap()))
    }

    @Test
    fun `the price of the link is always spelled out`() {
        val warning = issuedLinkWarning(issued)
        assertTrue(warning, warning.contains("любой"))
        assertTrue(warning, warning.contains("сутки"))

        val noExpiry = issuedLinkWarning(mapOf("entity.url" to "https://relay.example/d/abc"))
        assertTrue(noExpiry, noExpiry.contains("любой"))
        assertTrue(noExpiry, !noExpiry.contains("Живёт"))
    }
}
