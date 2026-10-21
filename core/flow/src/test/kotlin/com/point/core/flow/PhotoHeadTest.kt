package com.point.core.flow

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Заголовок снимка читается без Android: компьютеру нужен тот же ответ, что и телефону (#1379). */
class PhotoHeadTest {

    @Test fun `дата, место и поворот читаются из заголовка`() {
        val head = photoHead(jpeg(exif(ByteOrder.BIG_ENDIAN)))!!

        assertEquals("2026:10:21 15:30:00", head.shotAt)
        assertEquals(50.45, head.latitude!!, 1e-6)
        assertEquals(30.52, head.longitude!!, 1e-6)
        assertEquals(90, head.uprightDegrees)
    }

    @Test fun `порядок байт Intel читается так же`() {
        assertEquals(photoHead(jpeg(exif(ByteOrder.BIG_ENDIAN))), photoHead(jpeg(exif(ByteOrder.LITTLE_ENDIAN))))
    }

    @Test fun `южное и западное полушария — со знаком минус`() {
        val head = photoHead(jpeg(exif(ByteOrder.BIG_ENDIAN, latRef = 'S', lonRef = 'W')))!!

        assertEquals(-50.45, head.latitude!!, 1e-6)
        assertEquals(-30.52, head.longitude!!, 1e-6)
    }

    @Test fun `снимок без записей и не снимок вовсе ответа не дают`() {
        assertNull(photoHead(jpeg(null)))
        assertNull(photoHead("просто текст".toByteArray()))
        assertNull(photoHead(ByteArray(0)))
    }

    @Test fun `оборванный заголовок не роняет разбор`() {
        val whole = jpeg(exif(ByteOrder.BIG_ENDIAN))
        for (cut in 0 until whole.size) photoHead(whole.copyOf(cut))
    }

    /** SOI, при необходимости APP1 «Exif», и начало кадра — дальше разбор не заглядывает. */
    private fun jpeg(tiff: ByteArray?): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (tiff != null) {
            val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
            write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
            write(byteArrayOf(((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()))
            write(body)
        }
        write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2))
    }.toByteArray()

    /**
     * TIFF с тремя каталогами: основной (поворот 6, дата, ссылки), Exif (дата съёмки) и GPS
     * (50°27′0″ N, 30°31′12″ E).
     */
    private fun exif(order: ByteOrder, latRef: Char = 'N', lonRef: Char = 'E'): ByteArray {
        val b = ByteBuffer.allocate(202).order(order)
        b.put(if (order == ByteOrder.BIG_ENDIAN) "MM".toByteArray() else "II".toByteArray())
        b.putShort(42).putInt(8)

        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            b.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value)
        }
        fun inlineShort(tag: Int, value: Int) {
            b.putShort(tag.toShort()).putShort(3).putInt(1).putShort(value.toShort()).putShort(0)
        }
        fun inlineAscii(tag: Int, letter: Char) {
            b.putShort(tag.toShort()).putShort(2).putInt(2).put(letter.code.toByte()).put(0.toByte()).putShort(0)
        }

        b.putShort(4)
        inlineShort(0x0112, 6)
        entry(0x0132, 2, 20, 62)
        entry(0x8769, 4, 1, 82)
        entry(0x8825, 4, 1, 100)
        b.putInt(0)

        b.put("2026:10:21 15:30:00".toByteArray()).put(0.toByte())

        b.putShort(1)
        entry(0x9003, 2, 20, 62)
        b.putInt(0)

        b.putShort(4)
        inlineAscii(1, latRef)
        entry(2, 5, 3, 154)
        inlineAscii(3, lonRef)
        entry(4, 5, 3, 178)
        b.putInt(0)

        for ((d, m, s) in listOf(Triple(50, 27, 0), Triple(30, 31, 12))) {
            b.putInt(d).putInt(1).putInt(m).putInt(1).putInt(s).putInt(1)
        }
        return b.array()
    }
}
