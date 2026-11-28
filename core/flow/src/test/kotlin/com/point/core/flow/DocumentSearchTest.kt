package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск по прочитанному тексту документа (#1014): человек ищет слово в скане и слышит, сколько
 * мест нашлось и на каких страницах. Правила сравнения — те же, что у поиска по странице: один
 * запрос на одном документе не должен отвечать по-разному в зависимости от того, как его искали.
 */
class DocumentSearchTest {

    /** Три прочитанные страницы, склеенные так же, как их склеивает «Прочитать документ». */
    private val pages = listOf(
        1 to "Договір поставки № 17\nПостачальник: ТОВ «Світанок»",
        2 to "Сума: 20 842,50 грн\nІванов І.І. підписав",
        4 to "Додаток. Іванов\nІван Іванович — представник",
    )

    private val text = pages.joinToString("\n\n") { it.second }

    private val starts: List<PageStart> = run {
        var at = 0
        pages.map { (page, body) -> PageStart(page, at).also { at += body.length + 2 } }
    }

    private fun pagesFor(query: String): List<Int>? = pagesOf(findInText(text, query), starts)

    /** Какие номера страниц названы словами: первое число — счёт мест, остальные — страницы. */
    private fun pagesNamed(said: String): List<Int> =
        Regex("[0-9]+").findAll(said).map { it.value.toInt() }.drop(1).toList()

    @Test fun `слово находится на каждой странице, где оно есть, и страницы названы`() {
        val hits = findInText(text, "іванов")

        assertEquals("регистр не важен, все три места найдены", 3, hits.size)
        assertEquals(listOf(2, 4), pagesOf(hits, starts))

        val said = foundInDocumentLabel(hits.size, pagesOf(hits, starts))
        assertTrue("счёт мест — теми же словами, что у поиска по странице: $said", said.startsWith(foundOnPageLabel(3)))
        assertEquals("названы ровно те страницы, где нашлось: $said", listOf(2, 4), pagesNamed(said))
    }

    @Test fun `фраза, разорванная переносом строки, находится целиком`() {
        assertEquals(listOf(4), pagesFor("Іванов Іван"))
    }

    @Test fun `лишние пробелы, тире и кавычки находку не прячут`() {
        assertEquals(listOf(1), pagesFor("тов   світанок"))
        assertEquals(listOf(1), pagesFor("ТОВ \"Світанок\""))
        assertEquals(listOf(4), pagesFor("Іванович представник"))
    }

    @Test fun `сумма находится с разрядным пробелом и без, с точкой вместо запятой`() {
        assertEquals(listOf(2), pagesFor("20842.50"))
        assertEquals(listOf(2), pagesFor("20 842,50"))
        assertTrue("число без запятой — другое число", findInText(text, "2084250").isEmpty())
    }

    @Test fun `украинские буквы сравниваются без регистра`() {
        assertEquals(listOf(1), pagesFor("ДОГОВІР"))
        assertEquals(listOf(1), pagesFor("постачальник"))
        assertTrue("і и и — разные буквы, подмены нет", findInText(text, "Иванов").isEmpty())
    }

    @Test fun `находка принадлежит странице, на которой начинается, — и на самой границе тоже`() {
        val second = starts.first { it.page == 2 }.offset

        assertEquals(listOf(2), pagesOf(listOf(second), starts))
        assertEquals(listOf(1), pagesOf(listOf(second - 1), starts))
        assertEquals(listOf(1), pagesFor("Договір"))
    }

    @Test fun `без границ страниц ответ — те же слова, что у поиска по странице`() {
        val hits = findInText(text, "іванов")

        assertNull(pagesOf(hits, emptyList()))
        assertEquals(foundOnPageLabel(3), foundInDocumentLabel(hits.size, pagesOf(hits, emptyList())))
    }

    @Test fun `находку до первой известной страницы не приписывают никакой`() {
        assertNull(
            "часть находок без страницы — частичный ответ звучал бы как полный",
            pagesOf(listOf(0, 50), listOf(PageStart(2, 40))),
        )
    }

    @Test fun `ничего не нашлось — так и сказано, без номеров страниц`() {
        assertTrue(findInText(text, "Петренко").isEmpty())
        assertEquals(foundOnPageLabel(0), foundInDocumentLabel(0, emptyList()))
        assertEquals(foundOnPageLabel(0), foundInDocumentLabel(0, null))
    }

    @Test fun `одна страница называется одна`() {
        val said = foundInDocumentLabel(1, listOf(4))

        assertTrue(said, said.startsWith(foundOnPageLabel(1)))
        assertEquals(said, listOf(4), pagesNamed(said))
    }

    @Test fun `длинный список страниц не читается — названы первые и сколько ещё`() {
        assertEquals(listOf(1, 2, 3, 5), pagesNamed(foundInDocumentLabel(20, listOf(1, 2, 3, 5))))
        assertEquals(
            "на одну страницу больше — названа, а не «и ещё 1»",
            listOf(1, 2, 3, 4, 5, 6),
            pagesNamed(foundInDocumentLabel(20, listOf(1, 2, 3, 4, 5, 6))),
        )

        val long = foundInDocumentLabel(20, listOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(long, long.endsWith(com.point.core.model.tr("{0} и ещё {1}", "1, 2, 3, 4, 5", 3)))
    }

    @Test fun `запрос, в котором нечего искать, ничего не находит`() {
        assertTrue(findInText(text, " — ").isEmpty())
    }

    @Test fun `границы страниц читаются из знания и переживают испорченную пару`() {
        val meta = mapOf(META_OCR_TEXT_PAGES to "1:0, 3:140,мусор,0:5,2:-1,5:300")

        assertEquals(listOf(PageStart(1, 0), PageStart(3, 140), PageStart(5, 300)), pageStartsOf(meta))
        assertEquals(emptyList<PageStart>(), pageStartsOf(emptyMap()))
        assertEquals("1:0,3:140", pageStartsWire(listOf(PageStart(1, 0), PageStart(3, 140))))
    }

    /**
     * Границы — пометка при прочтении, а не отдельное знание: ушёл текст — ушли и они, а то
     * указывали бы в текст, которого нет. Перечитанный документ приносит свои границы, а не спор
     * с прежними.
     */
    @Test fun `границы уходят вместе с текстом и обновляются вместе с ним`() {
        val read = mapOf(META_OCR_TEXT_REF to "/a.txt", META_OCR_TEXT_PAGES to "1:0,2:10")

        assertFalse(META_OCR_TEXT_PAGES in withoutKnowledge(read, setOf(META_OCR_TEXT_REF)))

        val again = mergeKnowledge(
            read,
            mapOf(META_OCR_TEXT_REF to "/b.txt", META_OCR_TEXT_PAGES to "1:0,3:12"),
            REFRESHABLE_KNOWLEDGE,
        )
        assertEquals("1:0,3:12", again[META_OCR_TEXT_PAGES])
        assertFalse("прежние границы ушли в спор", (META_OCR_TEXT_PAGES + META_ALT_SUFFIX) in again)
    }
}
