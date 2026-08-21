package com.point.executors

import com.point.core.flow.collectionContent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * «Сканировать в PDF» и «Объединить в PDF» не схлопывают набор (#1002, решение владельца —
 * страница на фото): каждому фото набора — своя страница PDF, и идут они в порядке набора.
 *
 * Само рисование страниц — Android (`PdfDocument`), на JVM его не позвать; здесь проверяется
 * состав страниц: сколько их, по одной ли на файл и в том ли порядке, в каком набор показан.
 */
class ImagesToPdfTest {

    private fun setOfFiles(vararg names: String): File {
        val dir = Files.createTempDirectory("point-set").toFile().apply { deleteOnExit() }
        names.forEach { File(dir, it).apply { writeBytes(byteArrayOf(1)); deleteOnExit() } }
        return dir
    }

    @Test
    fun `набор из двух фото даёт две страницы — по одной на каждое фото`() {
        val photos = setOf("IMG_0002.jpg", "IMG_0001.jpg")

        val pages = pagesOf(setOfFiles(*photos.toTypedArray()))

        assertEquals(2, pages.size)
        assertEquals(photos, pages.map { it.name }.toSet())
    }

    @Test
    fun `страницы идут в порядке набора — как набор показан человеку`() {
        val dir = setOfFiles("стр 2.jpg", "стр 1.jpg", "Стр 3.jpg")
        val shown = collectionContent(dir.walkTopDown(), isFile = { it.isFile }, name = { it.name })
            .shown.map { it.name }

        assertEquals(shown, pagesOf(dir).map { it.name })
        assertEquals(listOf("стр 1.jpg", "стр 2.jpg", "Стр 3.jpg"), pagesOf(dir).map { it.name })
    }

    @Test
    fun `вложенная папка набора — не страница, а её файлы — страницы`() {
        val dir = setOfFiles("b.jpg")
        File(dir, "вложенная").apply { mkdirs(); deleteOnExit() }
            .let { File(it, "a.jpg").apply { writeBytes(byteArrayOf(1)); deleteOnExit() } }

        assertEquals(listOf("a.jpg", "b.jpg"), pagesOf(dir).map { it.name })
    }

    @Test
    fun `в PDF уходит весь набор, а не первый экран списка`() {
        val many = (1..COLLECTION_LIMIT_PLUS).map { "p%04d.jpg".format(it) }

        val pages = pagesOf(setOfFiles(*many.toTypedArray()))

        assertEquals(many, pages.map { it.name })
    }

    private companion object {
        /** Больше, чем показывается на экране набора за раз (`COLLECTION_ITEMS_LIMIT`). */
        const val COLLECTION_LIMIT_PLUS = com.point.core.flow.COLLECTION_ITEMS_LIMIT + 1
    }
}
