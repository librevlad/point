package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingUpscaleTest {

    @Test
    fun `мелкий кадр замера увеличивается, а эталонный - нет`() {

        assertEquals(3, readingUpscale(1000, 750))

        assertEquals(1, readingUpscale(4000, 3000))
    }

    @Test
    fun `кадр ровно на пороге уже считается крупным`() {
        assertEquals(1, readingUpscale(READING_FRAME_PX, 1000))
        assertEquals(2, readingUpscale(READING_FRAME_PX - 1, 1000))
    }

    @Test
    fun `увеличение доводит длинную сторону до порога, а не до круглого числа`() {

        assertEquals(3, readingUpscale(700, 500))

        assertEquals(2, readingUpscale(1024, 768))
    }

    @Test
    fun `выше потолка не растягиваем - интерполяция не добавит содержания`() {
        assertEquals(4, readingUpscale(120, 90))
        assertEquals(4, readingUpscale(16, 16))
    }

    @Test
    fun `вырожденный размер ничего не увеличивает`() {
        assertEquals(1, readingUpscale(0, 0))
        assertEquals(1, readingUpscale(-10, 100))
        assertEquals(1, readingUpscale(100, 0))
    }

    @Test
    fun `бюджет памяти отступает последним и режет увеличение, а не запрещает его`() {

        assertEquals(1, readingUpscale(1800, 1800))

        assertEquals(2, readingUpscale(1500, 1500))
    }

    @Test
    fun `бюджет считается по площади, а не по длинной стороне`() {

        assertEquals(2, readingUpscale(1800, 200))
    }

    @Test
    fun `знаем буквы - судим по буквам, а не по кадру`() {

        assertEquals(1, readingUpscale(900, 300, textHeightPx = 48))

        assertEquals(3, readingUpscale(900, 300))
    }

    @Test
    fun `крупный кадр с мелким кеглем увеличивается, пока хватает памяти`() {

        assertEquals(2, readingUpscale(1600, 1200, textHeightPx = 12))

        assertEquals(1, readingUpscale(4000, 3000, textHeightPx = 12))
    }

    @Test
    fun `буква ровно в цель увеличения не требует`() {
        assertEquals(1, readingUpscale(900, 300, textHeightPx = READING_TEXT_PX))
        assertEquals(2, readingUpscale(900, 300, textHeightPx = READING_TEXT_PX - 1))
    }

    @Test
    fun `бессмысленная высота буквы возвращает правило к размеру кадра`() {
        assertEquals(3, readingUpscale(1000, 750, textHeightPx = 0))
        assertEquals(3, readingUpscale(1000, 750, textHeightPx = -5))
    }

    @Test
    fun `высота слова - медиана, и заголовок её не сдвигает`() {
        val words = (1..12).map { word(it, heightPx = 20f) } + word(99, heightPx = 200f)
        assertEquals(20, typicalTextHeightPx(AtomLayer(words)))
    }

    @Test
    fun `горстка слов - не страница, судить не по чему`() {
        assertNull(typicalTextHeightPx(AtomLayer((1..9).map { word(it, heightPx = 20f) })))
        assertNull(typicalTextHeightPx(AtomLayer(emptyList())))
    }

    @Test
    fun `пустые слова в счёт не идут`() {
        val words = (1..12).map { word(it, heightPx = 20f) } + (1..30).map { word(it, heightPx = 3f, text = "  ") }
        assertEquals(20, typicalTextHeightPx(AtomLayer(words)))
    }

    @Test
    fun `буквы слоя, читанного с увеличением, приводятся к неувеличенному кадру`() {

        val enlarged = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3)
        val asIs = FrameTransform(sample = 1, uprightWidth = 1000, uprightHeight = 750)
        assertEquals(20, typicalTextHeightPx(AtomLayer((1..12).map { word(it, 20f) }, transform = enlarged)))
        assertEquals(60, typicalTextHeightPx(AtomLayer((1..12).map { word(it, 60f) }, transform = asIs)))
    }

    @Test
    fun `у кадра боком высота меряется в выпрямленной копии, а не в сыром боксе`() {
        val transform = FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 400, uprightHeight = 300)

        val upright = Box(10f, 10f, 50f, 30f)
        val raw = transform.toRaw(upright)
        assertEquals(20, typicalTextHeightPx(AtomLayer((1..12).map { atom(it, raw) }, transform = transform)))
    }

    @Test
    fun `шов увеличения зовётся ровно тогда, когда правило так решило`() {
        var calls = 0
        val upscaler = FrameUpscaler<Pair<Int, Int>> { frame, scale ->
            calls++
            frame.first * scale to frame.second * scale
        }

        val small = preparedForReading(1000 to 750, 1000, 750, upscaler = upscaler)
        assertEquals(3, small.scale)
        assertTrue(small.upscaled)
        assertEquals(3000 to 2250, small.frame)
        assertEquals(1, calls)

        val big = 4000 to 3000
        val untouched = preparedForReading(big, 4000, 3000, upscaler = upscaler)
        assertEquals(1, untouched.scale)
        assertFalse(untouched.upscaled)

        assertSame(big, untouched.frame)
        assertEquals(1, calls)
    }

    @Test
    fun `измеренные буквы доезжают до шва`() {
        val upscaler = FrameUpscaler<Int> { frame, scale -> frame * scale }
        assertEquals(1, preparedForReading(7, 900, 300, textHeightPx = 48, upscaler = upscaler).scale)
        assertEquals(4, preparedForReading(7, 900, 300, textHeightPx = 8, upscaler = upscaler).scale)
    }

    private fun word(index: Int, heightPx: Float, text: String = "слово") =
        atom(index, Box(0f, index * 100f, 50f, index * 100f + heightPx), text)

    private fun atom(index: Int, box: Box, text: String = "слово") =
        Atom(id = "w$index", text = text, box = box)
}
