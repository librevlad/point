package com.point.executors

import com.point.core.flow.ArchiveExtractor
import com.point.core.model.ActionResult
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

class ArchiveActionTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun extractorInto(dir: File) = object : ArchiveExtractor {
        override suspend fun extract(obj: PointObject) = ScratchRef(dir.absolutePath)
    }

    private val zip = PointObject("id", "application/zip", ScratchRef("/tmp/x.zip"), ObjectState(ObjectKind.ZIP))

    @Test
    fun `распакованный архив становится коллекцией со счётом файлов`() = runTest {
        val dir = tmp.newFolder("unpacked")
        File(dir, "a.txt").writeText("раз")
        File(dir, "b.txt").writeText("два")

        val result = ArchiveRealizer(extractorInto(dir)).perform(zip, null)

        assertTrue(result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.COLLECTION, out.type)
        assertEquals("2", out.metadata["count"])
    }

    @Test
    fun `пустой архив — восстановимая ошибка, а не пустая коллекция`() = runTest {
        val result = ArchiveRealizer(extractorInto(tmp.newFolder("empty"))).perform(zip, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `распаковка называет себя`() = runTest {
        val dir = tmp.newFolder("named")
        File(dir, "a.txt").writeText("раз")

        val heard = stagesHeard { ArchiveRealizer(extractorInto(dir)).perform(zip, null) }

        assertEquals(listOf("Распаковываю архив"), heard)
    }
}
