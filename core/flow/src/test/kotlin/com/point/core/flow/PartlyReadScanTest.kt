package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.tr
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Скан, прочитанный не целиком, не получает «Ничего не нашлось» и дочитывается (карточка «Не
 * отвечать «Ничего не нашлось» по скану, прочитанному не целиком», найдено вживую 28.11.2026).
 *
 * Скан на 110 страниц телефон прочитал на сотню и честно сказал «100 из 110», а «Найти в
 * документе» на слово со 107-й ответило «Ничего не нашлось» — ответ обо всём документе, хвост
 * которого никто не видел. Дочитать хвост было нечем: дверь чтения закрывалась, едва у документа
 * появлялся текст. То же на компьютере со страницами, на которых сервис отказал. А чистый лист в
 * середине скана делал прочитанный целиком документ «2 из 3» — неотличимо от непрочитанного.
 */
class PartlyReadScanTest {

    /**
     * Документ на [total] страниц, который растеризатор рисует как телефон: просимое, но не
     * больше [atMost] страниц за заход. Имена файлов — настоящие номера страниц.
     */
    private class Document(private val total: Int, private val atMost: Int = Int.MAX_VALUE) : PdfRasterizer {
        val asked = mutableListOf<IntRange>()

        override suspend fun rasterize(obj: PointObject) = rasterize(obj, ALL_PAGES)

        override suspend fun rasterize(obj: PointObject, pages: IntRange): RasterizedPages {
            asked += pages
            val drawn = pagesToDraw(pages, total, atMost)
            val dir = File.createTempFile("pages-", "").apply { delete(); mkdirs(); deleteOnExit() }
            drawn.forEach { File(dir, "page-%03d.jpg".format(it)).apply { writeBytes(ByteArray(4)); deleteOnExit() } }
            return RasterizedPages(ScratchRef(dir.absolutePath), total, first = drawn.first)
        }

        override suspend fun rasterizeFirstPage(obj: PointObject) = null
    }

    /** Читатель знает, какую страницу читает, по её номеру — и помнит, какие читал. */
    private class Reader(private val say: (Int) -> String) : TextRecognizer {
        val read = mutableListOf<Int>()

        override suspend fun recognize(obj: PointObject): String {
            val page = Regex("""page-(\d+)""").find(File(obj.uri.value).name)!!.groupValues[1].toInt()
            read += page
            return say(page)
        }
    }

    private val keeper = TextKeeper { _, text ->
        File.createTempFile("read-", ".txt").apply { deleteOnExit(); writeText(text) }.absolutePath
    }

    private val scan = PointObject(
        "pdf",
        "application/pdf",
        ScratchRef("/tmp/skan.pdf"),
        ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF)),
    )

    private val door = ReadDocumentCapability()

    /** Знание шага ложится на объект тем же слиянием, каким его кладёт продукт. */
    private fun PointObject.learned(result: ActionResult): PointObject {
        val found = (result as ActionResult.Done).findings!!
        return copy(
            state = found.features.fold(state) { known, feature -> known.with(feature) },
            metadata = mergeKnowledge(metadata, found.metadata, REFRESHABLE_KNOWLEDGE),
        )
    }

    private suspend fun PointObject.find(query: String): String =
        (FindRealizer().perform(this, query) as ActionResult.Done).message

    private fun PointObject.text(): String = File(metadata.getValue(META_OCR_TEXT_REF)).readText()

    private fun stateOf(obj: PointObject) = investigationStateOf(obj.metadata, KnownCapabilities.IMAGE_TEXT)

    /** Страница скана на 110 страниц: на 3-й — сумма, на 107-й — фамилия. */
    private fun page(number: Int): String = buildString {
        append("Сторінка $number.")
        if (number == 3) append(" Сума 1 450 грн")
        if (number == 107) append(" Одержувач Петренко")
    }

    @Test fun `заход рисует просимое в пределах документа и не больше предела`() {
        assertEquals(1..100, pagesToDraw(ALL_PAGES, pageCount = 110, atMost = 100))
        assertEquals(101..110, pagesToDraw(101..110, pageCount = 110, atMost = 100))
        assertEquals(101..200, pagesToDraw(101..250, pageCount = 250, atMost = 100))
        assertEquals(2..2, pagesToDraw(2..2, pageCount = 3))
        assertTrue("за концом документа нарисовано что-то", pagesToDraw(120..130, pageCount = 110).isEmpty())
    }

}
