package com.point.data

import com.point.core.model.CapabilityId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileFavoritesStoreTest {

    private val dir = Files.createTempDirectory("point-fav").toFile().apply { deleteOnExit() }
    private val store = FileFavoritesStore(dir)

    @Test
    fun `save then all round-trips`() = runTest {
        store.save("Картинка → PDF", listOf(CapabilityId("image"), CapabilityId("pdf")))
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals("Картинка → PDF", all[0].name)
        assertEquals(listOf("image", "pdf"), all[0].steps.map { it.value })
    }

    @Test
    fun `delete removes a chain`() = runTest {
        val chain = store.save("AI", listOf(CapabilityId("ai")))
        store.save("Keep", listOf(CapabilityId("share")))
        store.delete(chain.id)
        val names = store.all().map { it.name }
        assertEquals(listOf("Keep"), names)
    }

    @Test
    fun `empty store returns nothing`() = runTest {
        assertTrue(store.all().isEmpty())
    }
}
