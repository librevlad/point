package com.point.data

import com.google.zxing.WriterException
import com.point.core.flow.QR_MAX_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1084: общий потолок — обещание, которое телефон обязан выполнять.
 *
 * Потолок объявлен в `core` и одинаков на телефоне и на компьютере. Но кодирует на телефоне
 * библиотека, и до этих тестов никто не проверял, что она берёт ровно столько: потолок считался
 * по общей таблице вместимости, а библиотека тратила ещё полтора байта на объявление кодировки —
 * ровно на потолке человек получал бы «Data too big» вместо кода.
 */
class ZxingQrEncoderTest {

    @Test
    fun `the phone encoder takes the shared ceiling to the last byte`() {
        assertTrue(qrBitMatrix("x".repeat(QR_MAX_BYTES)).width > 0)
        assertTrue(qrBitMatrix("я".repeat(QR_MAX_BYTES / 2)).width > 0)
    }

    @Test
    fun `above the shared ceiling the phone encoder is the one that refuses`() {
        assertThrows(WriterException::class.java) { qrBitMatrix("x".repeat(QR_MAX_BYTES + 1)) }
    }

    /** #1084: длинный текст берёт самую большую версию — картинка обязана расти вместе с ней. */
    @Test
    fun `a long code is drawn big enough for a camera`() {
        val long = qrBitMatrix("x".repeat(QR_MAX_BYTES)).width
        assertTrue("на модуль пришлось меньше восьми точек — $long", long >= 177 * 8)

        assertEquals(640, qrBitMatrix("https://point.leerio.app/d/2f8c1b0a").width)
    }
}
