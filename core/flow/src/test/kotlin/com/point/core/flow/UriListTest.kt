package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
