package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcConfigTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `first load generates and persists a hex token`() {
        val store = FilePcConfig(tmp.root)
        val first = store.load()
        assertTrue(first.token.matches(Regex("[0-9a-f]{32}")))
        assertEquals(FilePcConfig.DEFAULT_PORT, first.port)
        assertEquals(first, FilePcConfig(tmp.root).load()) // survives a new instance
    }

    @Test
    fun `resetToken revokes by changing the token but keeps identity`() {
        val store = FilePcConfig(tmp.root)
        val before = store.load()
        val after = store.resetToken()
        assertNotEquals(before.token, after.token)
        assertEquals(before.name, after.name)
        assertEquals(after, store.load())
    }
}
