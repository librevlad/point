package com.point.core.flow

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

class PagesActionTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rasterizerInto(dir: File) = object : PdfRasterizer {
        override suspend fun rasterize(obj: PointObject) = ScratchRef(dir.absolutePath)
        override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = null
    }

    private fun rasterizerFailing(reason: String) = object : PdfRasterizer {
        override suspend fun rasterize(obj: PointObject): ScratchRef = error(reason)
        override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? = error(reason)
    }

    private val pdf = PointObject("id", "application/pdf", ScratchRef("/tmp/x.pdf"), ObjectState(ObjectKind.PDF))

    @Test
    fun `разобранный PDF становится коллекцией со счётом страниц`() = runTest {
        val dir = tmp.newFolder("pages")
        File(dir, "page-001.jpg").writeText("1")
        File(dir, "page-002.jpg").writeText("2")

        val result = PagesRealizer(rasterizerInto(dir)).perform(pdf, null)

        assertTrue(result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.COLLECTION, out.type)
        assertEquals("2", out.metadata["count"])
    }

    @Test
    fun `PDF без страниц — восстановимая ошибка, а не пустая коллекция`() = runTest {
        val result = PagesRealizer(rasterizerInto(tmp.newFolder("empty"))).perform(pdf, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    /**
     * #570: раньше человек читал «Не удалось разобрать PDF на страницы» — как будто сломался
     * Point. В документе просто нет страниц, и так это и называется.
     */
    @Test
    fun `документ без страниц называет пустоту, а не поломку разбора`() = runTest {
        val result = PagesRealizer(rasterizerFailing(com.point.core.flow.READER_NO_PAGES)).perform(pdf, null)

        assertTrue(result is ActionResult.Failure)
        assertEquals("В документе нет ни одной страницы", (result as ActionResult.Failure).reason)
        assertTrue(result.recoverable)
    }

    @Test
    fun `чужие слова о сбое наружу не выходят`() = runTest {
        val result = PagesRealizer(rasterizerFailing("Central Directory Entry not found")).perform(pdf, null)

        val reason = (result as ActionResult.Failure).reason
        assertEquals("Не удалось разобрать PDF на страницы", reason)
    }

    @Test
    fun `разбор на страницы называет себя`() = runTest {
        val dir = tmp.newFolder("named")
        File(dir, "page-001.jpg").writeText("1")

        val heard = stagesHeard { PagesRealizer(rasterizerInto(dir)).perform(pdf, null) }

        assertEquals(listOf("Разбираю PDF на страницы"), heard)
    }
}
