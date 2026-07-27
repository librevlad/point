package com.point.data

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The basket (#96): objects accumulate as COPIES in the app's own directory — the
 * scratch dies with each flow, the basket survives until an explicit clear.
 */
class FileBasketTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun obj(content: String, name: String? = null): PointObject {
        val f = File(tmp.root, "src-${System.nanoTime()}.txt").apply { writeText(content) }
        return PointObject(
            "id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT),
            metadata = name?.let { mapOf("name" to it) } ?: emptyMap(),
        )
    }

    private fun basket() = FileBasket(File(tmp.root, "usage"))

    @Test
    fun `add copies the file and returns the growing count`() = runTest {
        val basket = basket()
        assertEquals(1, basket.add(obj("первый", "a.txt")))
        assertEquals(2, basket.add(obj("второй", "b.txt")))

        val items = basket.items()
        assertEquals(2, items.size)
        assertEquals(listOf("первый", "второй"), items.map { File(it).readText() })
    }

    @Test
    fun `same display name never overwrites - copies get distinct files`() = runTest {
        val basket = basket()
        basket.add(obj("один", "receipt.jpg"))
        basket.add(obj("два", "receipt.jpg"))

        val items = basket.items()
        assertEquals(2, items.size)
        assertEquals(setOf("один", "два"), items.map { File(it).readText() }.toSet())
    }

    @Test
    fun `the source file stays untouched - the basket owns its copy`() = runTest {
        val src = obj("оригинал")
        basket().add(src)
        assertTrue(File(src.uri.value).exists())
    }

    @Test
    fun `clear empties the basket`() = runTest {
        val basket = basket()
        basket.add(obj("x"))
        basket.clear()
        assertEquals(0, basket.items().size)
        assertEquals(1, basket.add(obj("снова"))) // reusable after clear
    }

    @Test
    fun `a path separator in the display name cannot escape the basket dir`() = runTest {
        val basket = basket()
        basket.add(obj("зло", "../../evil.txt"))
        val items = basket.items()
        assertEquals(1, items.size)
        assertTrue(items[0].contains("basket"))
    }
}
