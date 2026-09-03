package com.point.core.flow

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

/**
 * Чтение офисного документа — одно на телефон и компьютер (#1379). Проверки переехали сюда
 * вместе с кодом из `:executors`: орган «куда положить текст» здесь подменён временным файлом.
 */
class OfficeActionTest {

    private val keeper = TextKeeper { _, text ->
        File.createTempFile("point-", ".txt").apply { deleteOnExit(); writeText(text) }.absolutePath
    }

    private fun extractor(text: String) = object : OfficeTextExtractor {
        override suspend fun extractText(obj: PointObject) = text

        // Тут проверяется чтение текста, а не разбор на слайды (#1105).
        override suspend fun slides(obj: PointObject) = emptyList<Pair<Int, String>>()
    }

    private val docx = PointObject(
        id = "d",
        mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        uri = ScratchRef("/tmp/акт.docx"),
        state = ObjectState(ObjectKind.OFFICE),
    )

    @Test
    fun `разбор документа называет себя теми же словами, что и «В PDF»`() = runTest {
        val heard = stagesHeard { OfficeRealizer(extractor("Акт выполненных работ"), keeper).perform(docx, null) }

        assertEquals(listOf(OFFICE_READ_STAGE), heard)
    }

    /** Текст остаётся у самого документа, а не уезжает во второй объект (#995). */
    @Test
    fun `извлечённый текст становится знанием документа`() = runTest {
        val result = OfficeRealizer(extractor("Акт выполненных работ"), keeper).perform(docx, null)

        val found = (result as ActionResult.Done).findings
        assertTrue(com.point.core.model.Feature.HAS_TEXT in found!!.features)
        assertEquals("Акт выполненных работ", File(found.metadata[META_OCR_TEXT_REF]!!).readText())
    }

    /**
     * Причина названа про этот файл, а не про чужой формат (#997).
     *
     * Раньше современная .docx слышала «старые .doc и .xls компьютер не открывает» — причину,
     * которая к ней не относится, и человек искал несуществующую проблему формата.
     */
    @Test
    fun `пустой документ отказывает с причиной, а не тихим пустым объектом`() = runTest {
        val result = OfficeRealizer(extractor(""), keeper).perform(docx, null)

        assertTrue("вышло: $result", result is ActionResult.Failure)
        val said = (result as ActionResult.Failure).reason
        assertTrue("причина свалена на чужой формат: $said", ".doc " !in said && ".xls" !in said)
        assertTrue("сказано, что случилось, но не что дальше: $said", said.split(" — ", ". ").size >= 2)
    }

    /**
     * Чтение и запись падают по-разному (#995, #997) — теперь и на телефоне.
     *
     * Пока они лежали в одном `runCatching`, осечка записи выходила человеку как «документ
     * повреждён»: документ был цел, а виноватым назначали его. Лучшее из двух прочтений жило
     * только на компьютере; общий исполнитель принёс его обоим.
     */
    @Test
    fun `не сохранившийся текст — беда записи, а не документа`() = runTest {
        val noDisk = TextKeeper { _, _ -> null }

        val result = OfficeRealizer(extractor("Акт"), noDisk).perform(docx, null)

        val said = (result as ActionResult.Failure).reason
        assertEquals(TEXT_NOT_KEPT, said)
        assertTrue("после осечки записи документ винить нельзя", "повреждён" !in said)
    }
}
