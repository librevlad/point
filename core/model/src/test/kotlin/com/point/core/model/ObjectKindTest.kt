package com.point.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectKindTest {

    @Test
    fun `the wire name of every built-in kind is stable`() {

        assertEquals("IMAGE", ObjectKind.IMAGE.name)
        assertEquals("TEXT", ObjectKind.TEXT.name)
        assertEquals("PDF", ObjectKind.PDF.name)
        assertEquals("ZIP", ObjectKind.ZIP.name)
        assertEquals("OFFICE", ObjectKind.OFFICE.name)
        assertEquals("URL", ObjectKind.URL.name)
        assertEquals("AUDIO", ObjectKind.AUDIO.name)
        assertEquals("COLLECTION", ObjectKind.COLLECTION.name)
        assertEquals("UNKNOWN", ObjectKind.UNKNOWN.name)
    }

    @Test
    fun `a built-in kind round-trips through valueOf as the same constant`() {
        ObjectKind.entries.forEach { kind ->
            assertEquals(kind, ObjectKind.valueOf(kind.name))
        }
    }

    @Test
    fun `an extraction kind this build never heard of survives a round-trip`() {

        val minted = ObjectKind.of("Organization")

        assertEquals("Organization", minted.name)
        assertEquals(minted, ObjectKind.valueOf("Organization"))
    }

    @Test
    fun `an unknown kind is not silently collapsed into UNKNOWN`() {

        assertNotEquals(ObjectKind.UNKNOWN, ObjectKind.valueOf("Identifier"))
    }

    @Test
    fun `entries lists only the file-level kinds`() {

        assertEquals(9, ObjectKind.entries.size)
        assertTrue(ObjectKind.of("Organization") !in ObjectKind.entries)
    }

    @Test
    fun `equality is by name, so kinds work as map keys and in when branches`() {
        assertEquals(ObjectKind.of("Identifier"), ObjectKind.of("Identifier"))

        val byKind = mapOf(ObjectKind.of("Identifier") to "ТТН")
        assertEquals("ТТН", byKind[ObjectKind.valueOf("Identifier")])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank name is a programming error, not an open kind`() {
        ObjectKind.valueOf("  ")
    }

    @Test
    fun `toString is the wire name, so logs and journal details stay readable`() {

        assertEquals("IMAGE", ObjectKind.IMAGE.toString())
        assertSame(ObjectKind.IMAGE.name, ObjectKind.IMAGE.name)
    }
}
