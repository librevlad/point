package com.point.executors

import com.point.core.flow.Exporter
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SaveAllActionTest {

    private class FakeExporter(private val failOn: Set<String> = emptySet()) : Exporter {
        val exported = mutableListOf<String>()
        override suspend fun export(obj: PointObject): String {
            val name = obj.metadata.getValue("name")
            if (name in failOn) error("нет места")
            exported += name
            return "Downloads/$name"
        }
    }

    private fun collectionOf(vararg names: String): PointObject {
        val dir = Files.createTempDirectory("point-collection-").toFile()
        dir.deleteOnExit()
        names.forEach { File(dir, it).apply { writeText(it); deleteOnExit() } }
        return PointObject(
            id = "c",
            mime = "application/octet-stream",
            uri = ScratchRef(dir.absolutePath),
            state = ObjectState(ObjectKind.COLLECTION),
        )
    }

    @Test
    fun `каждый файл коллекции называет себя номером из общего числа`() = runTest {
        val collection = collectionOf("1.jpg", "2.jpg", "3.jpg")

        val heard = stagesHeard { SaveAllRealizer(FakeExporter()).perform(collection, null) }

        assertEquals(listOf("Сохраняю 1 из 3", "Сохраняю 2 из 3", "Сохраняю 3 из 3"), heard)
    }

    @Test
    fun `счёт идёт по взятым в работу, а итог — по сохранённым`() = runTest {

        val exporter = FakeExporter(failOn = setOf("2.jpg"))
        val collection = collectionOf("1.jpg", "2.jpg", "3.jpg")

        val heard = stagesHeard {
            val result = SaveAllRealizer(exporter).perform(collection, null)
            assertEquals("Сохранено файлов: 2", (result as ActionResult.Done).message)
        }

        assertEquals(listOf("Сохраняю 1 из 3", "Сохраняю 2 из 3", "Сохраняю 3 из 3"), heard)
        assertEquals(listOf("1.jpg", "3.jpg"), exporter.exported.sorted())
    }

    @Test
    fun `в пустой коллекции сохранять нечего — и говорить не о чем`() = runTest {
        val empty = collectionOf()

        val heard = stagesHeard {
            val result = SaveAllRealizer(FakeExporter()).perform(empty, null)
            assertTrue(result is ActionResult.Failure)
        }

        assertTrue(heard.isEmpty())
    }
}
