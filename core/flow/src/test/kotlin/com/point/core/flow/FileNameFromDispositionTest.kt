package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Выложенное по ссылке приезжает под своим именем (#1083): имя берётся из
 * Content-Disposition, включая RFC 5987, а не выдумывается из адреса.
 */
class FileNameFromDispositionTest {

    @Test fun `простое имя в кавычках`() {
        assertEquals("schet.pdf", fileNameFromDisposition("attachment; filename=\"schet.pdf\""))
    }

    @Test fun `имя без кавычек`() {
        assertEquals("schet.pdf", fileNameFromDisposition("attachment; filename=schet.pdf"))
    }

    @Test fun `RFC 5987 с кириллицей`() {
        val expected = java.net.URLDecoder.decode("%D0%9F%D1%80%D0%BE%D0%B1%D0%B0%20%D1%81%D1%81%D1%8B%D0%BB%D0%BA%D0%B8.txt", "UTF-8")
        assertEquals(
            expected,
            fileNameFromDisposition("attachment; filename*=UTF-8''%D0%9F%D1%80%D0%BE%D0%B1%D0%B0%20%D1%81%D1%81%D1%8B%D0%BB%D0%BA%D0%B8.txt"),
        )
    }

    @Test fun `звёздочка важнее простого имени, когда есть обе`() {
        val expected = java.net.URLDecoder.decode("%D1%82%D0%BE%D1%87%D0%BD%D0%BE%D0%B5.pdf", "UTF-8")
        assertEquals(
            expected,
            fileNameFromDisposition("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%D1%82%D0%BE%D1%87%D0%BD%D0%BE%D0%B5.pdf"),
        )
    }

    @Test fun `пустой заголовок — нет имени, а не мусор`() {
        assertNull(fileNameFromDisposition(null))
        assertNull(fileNameFromDisposition(""))
        assertNull(fileNameFromDisposition("inline"))
    }
}
