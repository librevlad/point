package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Адрес из файла `text/uri-list` (#999): ссылка, переданная файлом, знает свой адрес, а
 * файл без адреса ссылкой не считается.
 */
class UriListTest {

    @Test
    fun `адрес — первая строка файла`() {
        val address = "https://example.com/pointtest?a=1"

        assertEquals(address, uriListAddress("$address\n"))
    }

    @Test
    fun `комментарии и пустые строки адресом не считаются`() {
        val address = "https://example.com/a"

        assertEquals(address, uriListAddress("# заголовок\r\n\r\n$address\r\nhttps://example.com/b\r\n"))
    }

    @Test
    fun `строка без схемы адресом не становится`() {
        assertNull(uriListAddress("example.com/a"))
        assertNull(uriListAddress("просто текст"))
        assertNull(uriListAddress("# только комментарий\n"))
        assertNull(uriListAddress(""))
    }

    @Test
    fun `по первым байтам адрес читается так же`() {
        val address = "https://example.com/pointtest?a=1"

        assertEquals(address, uriListAddress(address.toByteArray()))
        assertNull(uriListAddress(ByteArray(0)))
    }

    // ---- Одно правило на все двери, за которыми объект рождается из файла. ----

    @Test
    fun `объект-ссылка получает адрес из своего файла — с комментариями и переводами CRLF`() {
        val address = "https://example.com/pointtest?a=1"
        val file = file("# сохранено из браузера\r\n\r\n$address\r\nhttps://example.com/b\r\n")

        val known = linkObject(file.absolutePath).knowingAddress()

        assertEquals(address, known.metadata[META_ENTITY_URL])
        assertTrue("адрес есть — признак ссылки обязан стоять", known.state.has(Feature.HAS_URL))
    }

    @Test
    fun `объект не ссылка — файл не читается и знание не появляется`() {
        val file = file("https://example.com/a\n")

        val untouched = PointObject(
            id = "text",
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.TEXT),
        ).knowingAddress()

        assertNull(untouched.metadata[META_ENTITY_URL])
        assertFalse(untouched.state.has(Feature.HAS_URL))
    }

    @Test
    fun `адрес, приехавший знанием, остаётся своим — байты не переспрашиваются`() {
        val address = "https://example.com/приехало-знанием"

        val known = PointObject(
            id = "link",
            mime = "text/uri-list",
            uri = ScratchRef("нет такого файла"),
            state = ObjectState(ObjectKind.URL),
            metadata = mapOf(META_ENTITY_URL to address),
        ).knowingAddress()

        assertEquals(address, known.metadata[META_ENTITY_URL])
        assertTrue(known.state.has(Feature.HAS_URL))
    }

    @Test
    fun `в файле нет адреса — знание не выдумывается`() {
        val known = linkObject(file("адреса тут нет").absolutePath).knowingAddress()

        assertNull(known.metadata[META_ENTITY_URL])
        assertFalse(known.state.has(Feature.HAS_URL))
    }

    private fun linkObject(path: String) = PointObject(
        id = "link",
        mime = "text/uri-list",
        uri = ScratchRef(path),
        state = ObjectState(ObjectKind.URL),
    )

    private fun file(content: String): File =
        File.createTempFile("uri-list-", ".txt").apply { writeText(content); deleteOnExit() }
}
