package com.point.desktop

import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Пачка файлов — один объект-коллекция с детьми, как на телефоне (#1099).
 *
 * Прежде два перетащенных файла становились двумя отдельными объектами: один и тот же вход
 * на двух поверхностях давал две разные модели. Решение владельца: одна модель Graph
 * важнее удобства одной поверхности.
 */
class BatchIsOneCollectionTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun `пачка рождает коллекцию, дети читаются из манифеста`() {
        val inbox = Inbox(temp.newFolder("inbox"))
        val a = temp.newFile("drop-a.txt").apply { writeText("а") }
        val b = temp.newFile("drop-b.txt").apply { writeText("б") }

        val batch = inbox.addFiles(listOf(a.absolutePath, b.absolutePath))

        assertEquals(ObjectKind.COLLECTION, batch.obj.state.kind)
        assertEquals("2", batch.obj.metadata["collection.size"])
        assertEquals(listOf(a.absolutePath, b.absolutePath), collectionChildren(batch.obj))
        assertTrue("имя не говорит про набор", batch.obj.metadata["name"].orEmpty().isNotBlank())
    }

    @Test fun `одиночный файл коллекцией не становится`() {
        val single = Inbox(temp.newFolder("inbox2")).addFile(temp.newFile("one.txt").absolutePath)

        assertTrue(single.obj.state.kind != ObjectKind.COLLECTION)
        assertTrue(collectionChildren(single.obj).isEmpty())
    }
}
