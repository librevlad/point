package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FindActionTest {

    private val capability = FindCapability()

    private fun dump(vararg atoms: Atom): String {
        val file = File.createTempFile("atoms", ".tsv").apply { deleteOnExit() }
        file.writeText(AtomCodec.encode(AtomLayer(atoms.toList())))
        return file.absolutePath
    }

    private fun page(metadata: Map<String, String>) =
        PointObject("id", "image/jpeg", ScratchRef("/page.jpg"), ObjectState(ObjectKind.IMAGE), metadata)

    private val waybill = arrayOf(
        Atom("a1", "Одержувач", Box(10f, 100f, 120f, 120f)),
        Atom("a2", "Іванов", Box(125f, 100f, 195f, 120f)),
        Atom("a3", "Спожито", Box(10f, 200f, 90f, 220f)),
        Atom("a4", "20 842", Box(95f, 200f, 170f, 220f)),
    )

    @Test
    fun `действие есть у страницы со слоем слов`() {
        assertTrue(capability.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_WORD_LAYER))))

        assertFalse(capability.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_TEXT))))
        assertFalse(capability.accepts(ObjectState(ObjectKind.IMAGE)))
    }

    /**
     * Прочитанный скан ищется (#1014, решение владельца 19.08.2026): после «Прочитать документ»
     * текст — знание самого PDF, и «Найти в документе» обязано появиться, а не звать
     * раскладывать документ на страницы.
     */
    @Test
    fun `действие есть у прочитанного PDF — скана и документа со своим текстом`() {
        assertTrue(capability.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF, Feature.HAS_TEXT))))
        assertTrue(capability.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.HAS_TEXT))))

        assertFalse(capability.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF))))
        assertFalse(capability.accepts(ObjectState(ObjectKind.PDF)))
    }

    /**
     * Подсказка называет шаг, который у этого документа есть на самом деле (#1014, #1257): скан
     * готовится к поиску чтением, документ со своим слоем — извлечением текста.
     */
    @Test
    fun `на непрочитанном PDF действие называет недостающий шаг, а не молчит`() {
        val scan = ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF))
        val layered = ObjectState(ObjectKind.PDF)
        val scanHint = capability.missing(scan)
        val layeredHint = capability.missing(layered)

        assertNotNull("непрочитанный скан молчит, почему в нём не ищут", scanHint)
        assertNotNull("документ со своим слоем молчит, почему в нём не ищут", layeredHint)
        assertNotEquals("скан снова зовут раскладывать на страницы", "разложите на страницы", scanHint)
        assertNotEquals("скан и документ со своим слоем готовятся к поиску разными шагами", scanHint, layeredHint)
        assertTrue("названного шага у скана нет", ReadDocumentCapability().accepts(scan))
        assertTrue("названного шага у документа нет", com.point.core.flow.capabilities.PdfCapability().accepts(layered))

        assertNull(capability.missing(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `действие ничего не создаёт — находки показываются на этой же странице`() {
        val state = ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_WORD_LAYER))
        assertEquals(state, capability.produces(state))
        assertEquals(setOf(com.point.core.model.Intent.UNDERSTAND), capability.intents(state))
    }

    @Test
    fun `без запроса действие спрашивает, что искать`() = runTest {
        val obj = page(mapOf(META_OCR_ATOMS_REF to dump(*waybill)))
        val result = FindRealizer().perform(obj, null)

        assertTrue(result is ActionResult.NeedsInput)
        assertEquals("Что найти в документе?", (result as ActionResult.NeedsInput).prompt)

        assertTrue(FindRealizer().perform(obj, "  —  ") is ActionResult.NeedsInput)
    }

    @Test
    fun `найденное считается местами на странице`() = runTest {
        val obj = page(mapOf(META_OCR_ATOMS_REF to dump(*waybill)))

        assertEquals("Нашлось 1 место", (FindRealizer().perform(obj, "іванов") as ActionResult.Done).message)

        assertEquals("Нашлось 1 место", (FindRealizer().perform(obj, "20842") as ActionResult.Done).message)
    }

    @Test
    fun `ненайденное говорится прямо и не красится в отказ`() = runTest {
        val result = FindRealizer().perform(page(mapOf(META_OCR_ATOMS_REF to dump(*waybill))), "Петров")

        assertTrue(result is ActionResult.Done)
        assertEquals("Ничего не нашлось", (result as ActionResult.Done).message)
    }

    @Test
    fun `без слоя слов действие отказывает, а не отвечает пустотой`() = runTest {
        val result = FindRealizer().perform(page(mapOf(META_OCR_ATOMS_REF to "/нет/такого.tsv")), "Іванов")

        assertTrue(result is ActionResult.Failure)
        assertEquals("Страница ещё не прочитана — искать не в чем", (result as ActionResult.Failure).reason)
    }

    /** Скан, прочитанный «Прочитать документ»: текст рядом, границы страниц — знанием (#1014). */
    private fun readScan(text: String, pages: String?): PointObject {
        val file = File.createTempFile("read", ".txt").apply { deleteOnExit(); writeText(text) }
        return PointObject(
            "pdf",
            "application/pdf",
            ScratchRef("/skan.pdf"),
            ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF, Feature.HAS_TEXT)),
            mapOfNotNull(META_OCR_TEXT_REF to file.absolutePath, pages?.let { META_OCR_TEXT_PAGES to it }),
        )
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String>?): Map<String, String> = pairs.filterNotNull().toMap()

    @Test
    fun `в прочитанном скане запрос ищется по тексту и называет страницы`() = runTest {
        val scan = readScan("Одержувач Іванов\n\nСпожито 20 842\n\nІванов\nпідпис", pages = "1:0,2:18,5:34")

        val result = FindRealizer().perform(scan, "іванов")

        assertEquals(foundInDocumentLabel(2, listOf(1, 5)), (result as ActionResult.Done).message)
        assertEquals(
            "показание с разрядным пробелом — на своей странице",
            foundInDocumentLabel(1, listOf(2)),
            (FindRealizer().perform(scan, "20842") as ActionResult.Done).message,
        )
    }

    @Test
    fun `без границ страниц говорится, сколько нашлось, — без выдуманных номеров`() = runTest {
        val result = FindRealizer().perform(readScan("Іванов і ще раз Іванов", pages = null), "Іванов")

        assertEquals(foundOnPageLabel(2), (result as ActionResult.Done).message)
    }

    @Test
    fun `в прочитанном скане нет слова — так и сказано, без отказа`() = runTest {
        val result = FindRealizer().perform(readScan("Одержувач Іванов", pages = "1:0"), "Петров")

        assertTrue("ненайденное выдано за сбой: $result", result is ActionResult.Done)
        assertEquals(foundOnPageLabel(0), (result as ActionResult.Done).message)
    }

    @Test
    fun `у прочитанного скана без запроса действие спрашивает, что искать`() = runTest {
        val asked = FindRealizer().perform(readScan("Одержувач Іванов", pages = "1:0"), null)
        val onPage = FindRealizer().perform(page(mapOf(META_OCR_ATOMS_REF to dump(*waybill))), null)

        assertEquals(
            "документ спрашивает о запросе теми же словами, что страница",
            (onPage as ActionResult.NeedsInput).prompt,
            (asked as ActionResult.NeedsInput).prompt,
        )
    }

    /**
     * Текст пропал — искать было не в чем, и это отказ операции, а не «ничего не нашлось»:
     * ответ «не нашлось» про документ, который никто не просмотрел, — ложь о документе.
     */
    @Test
    fun `пропавший текст документа — отказ его словами, а не «ничего не нашлось»`() = runTest {
        val gone = readScan("Іванов", pages = "1:0").let {
            it.copy(metadata = it.metadata + (META_OCR_TEXT_REF to "/нет/такого.txt"))
        }

        val result = FindRealizer().perform(gone, "Іванов")

        assertTrue("искать было не в чем, а ответ — как будто искали: $result", result is ActionResult.Failure)
        val said = (result as ActionResult.Failure).reason
        assertNotEquals(foundOnPageLabel(0), said)
        assertTrue("отказ говорит о странице, а не о документе: $said", "документ" in said.lowercase())
    }

    @Test
    fun `у PDF без прочитанного текста — отказ словами про документ`() = runTest {
        val unread = PointObject("pdf", "application/pdf", ScratchRef("/skan.pdf"), ObjectState(ObjectKind.PDF))

        val result = FindRealizer().perform(unread, "Іванов")

        val said = (result as ActionResult.Failure).reason
        assertTrue("отказ говорит о странице, а не о документе: $said", "документ" in said.lowercase())
    }

    /** Экран телефона ищет подсветкой ровно там, где исполнитель искал бы по слою слов. */
    @Test
    fun `подсветка на странице — у страницы, а прочитанный скан ищется по тексту`() {
        assertTrue(findShowsOnPage(page(mapOf(META_OCR_ATOMS_REF to "/atoms.tsv"))))
        assertTrue("страница без слоя называет причину сама, как прежде", findShowsOnPage(page(emptyMap())))

        val scan = readScan("Іванов", pages = "1:0")
        assertFalse(findShowsOnPage(scan))
        assertTrue(findShowsOnPage(scan.copy(metadata = scan.metadata + (META_OCR_ATOMS_REF to "/atoms.tsv"))))
    }
}
