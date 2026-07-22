package com.point.data

import com.point.core.flow.ObjectClassifier
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileHistoryStoreTest {

    private val dir = Files.createTempDirectory("point-history").toFile().apply { deleteOnExit() }
    private val store = FileHistoryStore(dir, ObjectClassifier())

    private fun textObject(id: String, content: String, name: String): PointObject {
        val file = File.createTempFile("src-", ".txt").apply { writeText(content); deleteOnExit() }
        return PointObject(
            id = id,
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.TEXT),
            metadata = mapOf("name" to name),
        )
    }

    @Test
    fun `record then recent round-trips, newest first`() = runTest {
        store.record(textObject("a", "first", "a.txt"))
        store.record(textObject("b", "second", "b.txt"))

        val recent = store.recent()
        assertEquals(listOf("b", "a"), recent.map { it.id })
        assertEquals("b.txt", recent[0].name)
        assertEquals(ObjectKind.TEXT, recent[0].kind)
    }

    @Test
    fun `open re-materialises the persisted copy`() = runTest {
        store.record(textObject("a", "hello", "a.txt"))
        val reopened = store.open("a")!!
        // Fresh object id, but the bytes come from the persistent history copy.
        assertEquals("hello", File(reopened.uri.value).readText())
        assertEquals(ObjectKind.TEXT, reopened.state.kind)
    }

    @Test
    fun `same id de-duplicates keeping the latest`() = runTest {
        store.record(textObject("a", "v1", "old.txt"))
        store.record(textObject("a", "v2", "new.txt"))
        val recent = store.recent()
        assertEquals(1, recent.size)
        assertEquals("new.txt", recent[0].name)
    }

    @Test
    fun `open returns null for unknown id`() = runTest {
        assertNull(store.open("missing"))
    }

    @Test
    fun `clearAll wipes history`() = runTest {
        store.record(textObject("a", "x", "a.txt"))
        store.clearAll()
        assertTrue(store.recent().isEmpty())
    }
}
