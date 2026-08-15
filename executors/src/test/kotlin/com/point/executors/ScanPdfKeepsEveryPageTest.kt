package com.point.executors

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #1002: «Сканировать в PDF» схлопывало несколько страниц в одну.
 *
 * Человек даёт набор снимков и ждёт документ, где страниц ровно столько же и порядок тот же.
 * Сборка брала файлы по кодам символов одного лишь имени — одноимённые страницы из разных
 * папок набора шли вперемешку, `IMG_10` опережал `IMG_2` — и молча пропускала всё, что не
 * прочиталось: из двух снимков выходила одна страница без единого слова о потере.
 */
class ScanPdfKeepsEveryPageTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `сколько снимков в наборе — столько и страниц`() {
        val dir = tmp.newFolder("nabor")
        File(dir, "foto-1.jpg").writeText("1")
        File(dir, "foto-2.jpg").writeText("2")
        File(dir, "foto-3.jpg").writeText("3")

        assertEquals(3, pdfPageOrder(dir).size)
    }

    @Test
    fun `страницы идут по-человечески, а не по кодам символов`() {
        val dir = tmp.newFolder("mnogo")
        listOf("IMG_10.jpg", "IMG_2.jpg", "IMG_1.jpg").forEach { File(dir, it).writeText(it) }

        assertEquals(
            listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_10.jpg"),
            pdfPageOrder(dir).map { it.name },
        )
    }

    /** Одинаковое имя в разных папках — это две разные страницы, а не одна. */
    @Test
    fun `одноимённые снимки из разных папок остаются разными страницами`() {
        val dir = tmp.newFolder("dve-papki")
        val first = File(dir, "a").apply { mkdirs() }
        val second = File(dir, "b").apply { mkdirs() }
        File(first, "page.jpg").writeText("a")
        File(second, "page.jpg").writeText("b")

        val order = pdfPageOrder(dir)

        assertEquals(2, order.size)
        assertEquals(2, order.map { it.absolutePath }.toSet().size)
    }

    /** Папка держится вместе: страницы одного каталога не перемешиваются с чужими. */
    @Test
    fun `папка набора не рассыпается между чужими страницами`() {
        val dir = tmp.newFolder("gruppy")
        val first = File(dir, "a").apply { mkdirs() }
        val second = File(dir, "b").apply { mkdirs() }
        listOf("1.jpg", "9.jpg").forEach { File(first, it).writeText(it) }
        listOf("2.jpg", "3.jpg").forEach { File(second, it).writeText(it) }

        val pages = pdfPageOrder(dir)

        assertEquals(listOf("a", "a", "b", "b"), pages.map { it.parentFile?.name })
        assertEquals(listOf("1.jpg", "9.jpg", "2.jpg", "3.jpg"), pages.map { it.name })
    }

    @Test
    fun `непрочитанный снимок не исчезает молча`() {
        val reason = pdfRefusal(unread = listOf("foto-2.jpg"), pages = 1)

        assertTrue(reason.orEmpty().contains("foto-2.jpg"))
        assertFalse(reason == NO_IMAGES_FOR_PDF)
    }

    /** Договор в наборе рядом с фотографиями страницей не был и потерей не считается. */
    @Test
    fun `не-снимок в наборе отказа не вызывает`() {
        assertNull(pdfRefusal(unread = listOf("dogovor.txt", "smeta.xlsx"), pages = 2))
    }

    /**
     * Набор из одних лишь нечитаемых снимков раньше отвечал «нет изображений» — неправда:
     * изображения были, их не удалось прочитать.
     */
    @Test
    fun `нечитаемые снимки не выдаются за отсутствие снимков`() {
        val reason = pdfRefusal(unread = listOf("chek.jpg"), pages = 0)

        assertFalse(reason == NO_IMAGES_FOR_PDF)
        assertTrue(reason.orEmpty().contains("chek.jpg"))
    }

    @Test
    fun `набору без единого изображения по-прежнему отказывают`() {
        assertEquals(NO_IMAGES_FOR_PDF, pdfRefusal(unread = listOf("zametka.txt"), pages = 0))
    }

    @Test
    fun `собранному документу говорить нечего`() {
        assertNull(pdfRefusal(unread = emptyList(), pages = 2))
    }
}
