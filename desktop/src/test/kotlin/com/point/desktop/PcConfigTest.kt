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
    fun `первая загрузка даёт имя и переживает новый запуск`() {
        val store = FilePcConfig(tmp.root)
        val first = store.load()

        assertTrue("имя обязано быть — под ним компьютер виден в круге", first.name.isNotBlank())
        assertEquals(first, FilePcConfig(tmp.root).load())
    }

    @Test
    fun `ключи компьютера рождаются один раз и переживают перезапуск`() {
        val first = FileDeviceKeys(tmp.root).keys()
        val again = FileDeviceKeys(tmp.root).keys()

        assertEquals("сменившийся ключ сделал бы нечитаемым всё, что уже лежит в ящике", first, again)
        assertTrue(first.publicKey.isNotBlank() && first.privateKey.isNotBlank())
    }

    @Test
    fun `у другого компьютера — другие ключи`() {
        val other = TemporaryFolder().apply { create() }
        assertNotEquals(FileDeviceKeys(tmp.root).keys().publicKey, FileDeviceKeys(other.root).keys().publicKey)
        other.delete()
    }
}
