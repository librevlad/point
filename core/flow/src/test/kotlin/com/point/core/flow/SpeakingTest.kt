package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение вслух: резка текста, склейка кусков, язык (#442).
 *
 * Человек слушает результат за рулём. Ошибка здесь не выглядит ошибкой — она звучит: обрыв
 * на середине слова, тишина после первой минуты, английский голос на русском тексте.
 */
class SpeakingTest {

    // --- на сколько кусков резать -------------------------------------------------------

    @Test
    fun `короткий текст читается за один заход`() {
        assertEquals(listOf("Привет"), speechParts("Привет", 100))
    }

    @Test
    fun `пустому тексту нечего читать`() {
        assertTrue(speechParts("   ", 100).isEmpty())
    }

    @Test
    fun `длинный текст режется по предложениям, а не посреди слова`() {
        val text = "Первое предложение. Второе предложение! Третье предложение?"

        val parts = speechParts(text, 25)

        assertTrue("кусок длиннее предела: $parts", parts.all { it.length <= 25 })
        assertTrue("слово разорвано: $parts", parts.none { it.endsWith("предлож") })
        assertEquals("текст потерялся при резке", text.filter { !it.isWhitespace() }, parts.joinToString("").filter { !it.isWhitespace() })
    }

    @Test
    fun `предложение длиннее предела режется по словам`() {
        val text = "слово ".repeat(20).trim()

        val parts = speechParts(text, 20)

        assertTrue("кусок длиннее предела: $parts", parts.all { it.length <= 20 })
        assertTrue("слово разорвано: $parts", parts.all { it.split(' ').all { w -> w == "слово" } })
    }

    /** Ни один кусок не теряется: слушатель узнает о пропаже, только дослушав до конца. */
    @Test
    fun `весь текст доезжает до звука`() {
        val text = (1..200).joinToString(" ") { "предложение номер $it." }

        val parts = speechParts(text, 200)

        assertEquals(
            text.filter { !it.isWhitespace() },
            parts.joinToString(" ").filter { !it.isWhitespace() },
        )
    }

    // --- склейка ------------------------------------------------------------------------

    /** Кусок, как его пишет чтец: RIFF, поле формата, потом звук. */
    private fun wav(sound: ByteArray, extraChunk: String? = null): ByteArray {
        fun int(value: Int) = ByteArray(4) { ((value shr (8 * it)) and 0xFF).toByte() }
        fun id(name: String) = name.toByteArray(Charsets.US_ASCII)

        val fmt = id("fmt ") + int(16) + ByteArray(16)
        val extra = extraChunk?.let { id(it) + int(4) + ByteArray(4) } ?: ByteArray(0)
        val data = id("data") + int(sound.size) + sound
        val body = id("WAVE") + fmt + extra + data
        return id("RIFF") + int(body.size) + body
    }

    @Test
    fun `куски склеиваются в одну запись`() {
        val joined = Wav.join(listOf(wav(byteArrayOf(1, 2)), wav(byteArrayOf(3, 4, 5))))

        assertEquals("звук потерялся", 44 + 5, joined.size)
        assertEquals("звук перепутан", listOf<Byte>(1, 2, 3, 4, 5), joined.drop(44))
    }

    /** Чужой чтец вправе вписать свои поля перед звуком — они не должны попасть в запись. */
    @Test
    fun `лишние поля заголовка не попадают в звук`() {
        val joined = Wav.join(
            listOf(wav(byteArrayOf(1, 2), extraChunk = "LIST"), wav(byteArrayOf(3, 4))),
        )

        val sound = joined.drop(joined.size - 4)
        assertEquals("в звук попал заголовок", listOf<Byte>(1, 2, 3, 4), sound)
    }

    /** Длины в заголовке обязаны сойтись: иначе плеер услышит только первый кусок. */
    @Test
    fun `в заголовке стоит длина всей записи`() {
        val joined = Wav.join(listOf(wav(ByteArray(10)), wav(ByteArray(6))))

        fun intAt(at: Int) = (0..3).sumOf { (joined[at + it].toInt() and 0xFF) shl (8 * it) }

        assertEquals("длина файла не сошлась", joined.size - 8, intAt(4))
        assertEquals("длина звука не сошлась", 16, intAt(40))
    }

    @Test
    fun `один кусок остаётся собой`() {
        val one = wav(byteArrayOf(7, 7))

        assertTrue(Wav.join(listOf(one)).contentEquals(one))
    }

    /** Чужой файл не склеивается молча: испорченная запись хуже названного отказа. */
    @Test
    fun `не звук — не склеивается`() {
        val broken = runCatching { Wav.join(listOf(ByteArray(44), ByteArray(44))) }

        assertTrue("испортили запись молча", broken.exceptionOrNull() is Wav.NotWav)
    }

    // --- язык ---------------------------------------------------------------------------

    @Test
    fun `язык текста определяется по письму`() {
        assertEquals("ru", languageOfText("Здравствуйте, это обычный русский текст"))
        assertEquals("en", languageOfText("Hello, this is plain English text"))
        assertEquals("uk", languageOfText("Вітаю, це український текст із власними літерами"))
    }

    @Test
    fun `в тексте без букв языка нет`() {
        assertNull(languageOfText("1234 :: 5678"))
    }

    /** Цифры и знаки не делают русский текст английским. */
    @Test
    fun `числа не сбивают язык`() {
        assertEquals("ru", languageOfText("Счёт 4417 на сумму 12 500 грн, оплатить до 30.09.2025"))
    }
}
