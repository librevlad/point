package com.point.core.flow

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrMatrixTest {

    private fun roundTrip(text: String): String {
        val matrix = qrMatrix(text)
        assertNotNull("не закодировалось: $text", matrix)
        return Decoder().decode(matrix!!.toZxing()).text
    }

    private fun QrMatrix.toZxing(): BitMatrix =
        BitMatrix(size, size).also { bits ->
            for (y in 0 until size) for (x in 0 until size) if (this[x, y]) bits.set(x, y)
        }

    @Test
    fun `a drop link reads back exactly`() {
        val link = "https://point.leerio.app/d/2f8c1b0a4e6d9c3f5a7b1e2d4c6f8a0b1c3d5e7f"
        assertEquals(link, roundTrip(link))
    }

    @Test
    fun `a receiving link reads back exactly`() {
        val link = "https://point.leerio.app/u/nQ8vXk2mR7pLdT4wZs1aYbCe9Gh"
        assertEquals(link, roundTrip(link))
    }

    @Test
    fun `both ends of the supported length read back`() {
        assertEquals("a", roundTrip("a"))
        val long = "https://example.org/" + "x".repeat(QR_MAX_BYTES - 20)
        assertEquals(long, roundTrip(long))
    }

    @Test
    fun `cyrillic survives the round trip`() {
        assertEquals("Привет, Point", roundTrip("Привет, Point"))
    }

    @Test
    fun `too long is refused instead of truncated`() {
        assertNull(qrMatrix("x".repeat(QR_MAX_BYTES + 1)))
        assertNull(qrMatrix(""))
    }

    @Test
    fun `the cap counts bytes not characters`() {
        assertNull(qrMatrix("я".repeat(QR_MAX_BYTES)))
        assertNotNull(qrMatrix("я".repeat(QR_MAX_BYTES / 2)))
    }

    @Test
    fun `the smallest fitting version is chosen`() {
        assertEquals(21, qrMatrix("x".repeat(14))!!.size)
        assertEquals(25, qrMatrix("x".repeat(15))!!.size)
        assertEquals(177, qrMatrix("x".repeat(QR_MAX_BYTES))!!.size)
    }

    /** #1084: текст растёт — растёт и код, а не упирается в потолок посреди дороги. */
    @Test
    fun `the code grows with the text instead of refusing`() {
        var previous = 0
        for (length in listOf(20, 100, 300, 700, 1500, QR_MAX_BYTES)) {
            val size = qrMatrix("x".repeat(length))!!.size
            assertTrue("$length знаков: код не вырос ($size)", size >= previous)
            previous = size
        }
        assertEquals(177, previous)
    }

    /** #1084: текст из карточки — около ста семидесяти знаков кириллицы: телефон его кодировал,
     *  компьютер отвергал на своих ста шести байтах. */
    @Test
    fun `the text the phone encoded reads back here too`() {
        val text = "Оплата 4 500 ₽ до 25.08.2026, тел. +7 900 123-45-67, почта sales@example.org — " +
            "счёт № 1084 от 17 августа, получатель ООО «Точка», назначение: сканирование листов"
        assertTrue("тот случай был длиннее прежнего потолка ПК", text.toByteArray().size > 106)
        assertEquals(text, roundTrip(text))
    }

    /** #1084: старшие версии — свои квадраты выравнивания, номер версии и неравные блоки. */
    @Test
    fun `long texts of every size read back exactly`() {
        for (length in listOf(120, 200, 400, 800, 1200, 1500, 2000, QR_MAX_BYTES)) {
            val text = "point-" + "x".repeat(length - 6)
            assertEquals("$length знаков", text, roundTrip(text))
        }
    }

    @Test
    fun `finder patterns stand in three corners`() {
        val m = qrMatrix("https://point.example/d/abc")!!
        for ((cx, cy) in listOf(3 to 3, m.size - 4 to 3, 3 to m.size - 4)) {

            assertTrue("зрачок ($cx,$cy)", m[cx, cy] && m[cx - 1, cy] && m[cx + 1, cy])
            assertTrue("светлое кольцо ($cx,$cy)", !m[cx - 2, cy] && !m[cx + 2, cy] && !m[cx, cy - 2])
            assertTrue("тёмная рамка ($cx,$cy)", m[cx - 3, cy] && m[cx + 3, cy] && m[cx, cy - 3])
        }
    }

    @Test
    fun `the timing line alternates`() {
        val m = qrMatrix("https://point.example/d/abc")!!
        for (i in 8 until m.size - 8) {
            assertEquals("строка $i", i % 2 == 0, m[i, 6])
            assertEquals("столбец $i", i % 2 == 0, m[6, i])
        }
    }

    @Test
    fun `outside the matrix is light`() {
        val m = qrMatrix("point")!!
        assertTrue(!m[-1, 0] && !m[0, -1] && !m[m.size, 0] && !m[0, m.size])
    }

    @Test
    fun `encoding is deterministic`() {
        val a = qrMatrix("https://point.example/u/box")!!
        val b = qrMatrix("https://point.example/u/box")!!
        assertEquals(a.size, b.size)
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a[x, y], b[x, y])
    }
}
