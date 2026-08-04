package com.point.core.flow

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кодировщик QR (#388) экзаменует **чужой декодер**.
 *
 * Проверять свой кодировщик своими же представлениями о стандарте — значит проверять, что код
 * согласен сам с собой. Поэтому матрица отдаётся декодеру ZXing (чистая Java, только в тестах) и
 * должна вернуть ровно ту строку, которую кодировали: так же её прочитает камера чужого телефона.
 */
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

    /** Короткая строка и длина под самый потолок — обе границы диапазона версий. */
    @Test
    fun `both ends of the supported length read back`() {
        assertEquals("a", roundTrip("a"))
        val long = "https://example.org/" + "x".repeat(QR_MAX_BYTES - 20)
        assertEquals(long, roundTrip(long))
    }

    /** Кириллица едет байтами UTF-8: «отчёт» на том конце обязан остаться отчётом. */
    @Test
    fun `cyrillic survives the round trip`() {
        assertEquals("Привет, Point", roundTrip("Привет, Point"))
    }

    /** Длиннее потолка — молчание, а не обрезанная ссылка, ведущая не туда. */
    @Test
    fun `too long is refused instead of truncated`() {
        assertNull(qrMatrix("x".repeat(QR_MAX_BYTES + 1)))
        assertNull(qrMatrix(""))
    }

    /** Кириллица считается байтами UTF-8, а не знаками: 106 «я» — это 212 байт. */
    @Test
    fun `the cap counts bytes not characters`() {
        assertNull(qrMatrix("я".repeat(QR_MAX_BYTES)))
        assertNotNull(qrMatrix("я".repeat(QR_MAX_BYTES / 2)))
    }

    /** Версия берётся наименьшая из подходящих: код не должен быть крупнее, чем нужно. */
    @Test
    fun `the smallest fitting version is chosen`() {
        assertEquals(21, qrMatrix("x".repeat(14))!!.size)   // версия 1
        assertEquals(25, qrMatrix("x".repeat(15))!!.size)   // версия 2
        assertEquals(41, qrMatrix("x".repeat(QR_MAX_BYTES))!!.size) // версия 6
    }

    /** Три «глаза» по углам — по ним камера вообще находит код. */
    @Test
    fun `finder patterns stand in three corners`() {
        val m = qrMatrix("https://point.example/d/abc")!!
        for ((cx, cy) in listOf(3 to 3, m.size - 4 to 3, 3 to m.size - 4)) {
            // Глаз — это тёмный зрачок 3×3, светлое кольцо и тёмная рамка вокруг него.
            assertTrue("зрачок ($cx,$cy)", m[cx, cy] && m[cx - 1, cy] && m[cx + 1, cy])
            assertTrue("светлое кольцо ($cx,$cy)", !m[cx - 2, cy] && !m[cx + 2, cy] && !m[cx, cy - 2])
            assertTrue("тёмная рамка ($cx,$cy)", m[cx - 3, cy] && m[cx + 3, cy] && m[cx, cy - 3])
        }
    }

    /** Синхрополоса чередуется — по ней читают шаг сетки. */
    @Test
    fun `the timing line alternates`() {
        val m = qrMatrix("https://point.example/d/abc")!!
        for (i in 8 until m.size - 8) {
            assertEquals("строка $i", i % 2 == 0, m[i, 6])
            assertEquals("столбец $i", i % 2 == 0, m[6, i])
        }
    }

    /** За краем — светло: рисующему не нужно проверять границы, чтобы отбить поля тишины. */
    @Test
    fun `outside the matrix is light`() {
        val m = qrMatrix("point")!!
        assertTrue(!m[-1, 0] && !m[0, -1] && !m[m.size, 0] && !m[0, m.size])
    }

    /** Один и тот же текст даёт один и тот же код: в матрице нет ничего случайного. */
    @Test
    fun `encoding is deterministic`() {
        val a = qrMatrix("https://point.example/u/box")!!
        val b = qrMatrix("https://point.example/u/box")!!
        assertEquals(a.size, b.size)
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a[x, y], b[x, y])
    }
}
