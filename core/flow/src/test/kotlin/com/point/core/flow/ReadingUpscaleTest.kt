package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило увеличения кадра перед чтением (#273).
 *
 * Живой повод — замер зрячих моделей 04.08.2026 (`docs/VISION-MODELS.md`): из шести порч эталонной
 * ведомости провалилась ровно одна — кадр в четверть разрешения (20 строк из 24, итог мимо на
 * 8300), и он же прочитался целиком после обычного увеличения вчетверо. Здесь закреплено, что
 * правило включается на таком кадре и **не** включается на обычном фото: увеличение вчетверо это в
 * шестнадцать раз больше пикселей, и платить их за кадр, который и так читается, незачем.
 *
 * Числа замера — 1000×750 (провалившийся) и 4000×3000 (эталон) — стоят в тестах дословно, а не
 * округлённые: правило судится тем кадром, который его породил.
 */
class ReadingUpscaleTest {

    @Test
    fun `мелкий кадр замера увеличивается, а эталонный - нет`() {
        // Кадр, на котором чтение провалилось: четверть от 4000×3000.
        assertEquals(3, readingUpscale(1000, 750))
        // Сам эталон: он читается дословно всеми маршрутами — трогать нечего.
        assertEquals(1, readingUpscale(4000, 3000))
    }

    @Test
    fun `кадр ровно на пороге уже считается крупным`() {
        assertEquals(1, readingUpscale(READING_FRAME_PX, 1000))
        assertEquals(2, readingUpscale(READING_FRAME_PX - 1, 1000))
    }

    @Test
    fun `увеличение доводит длинную сторону до порога, а не до круглого числа`() {
        // 700 → ×3 (2100) хватает; ×4 не нужен.
        assertEquals(3, readingUpscale(700, 500))
        // 1024 → ×2 ровно в порог.
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

    /**
     * Предел памяти — тот самый, ради которого он и заведён: ×4 это в шестнадцать раз больше
     * пикселей, и без потолка мелкий-но-не-крошечный кадр попросил бы полсотни мегапикселей.
     */
    @Test
    fun `бюджет памяти отступает последним и режет увеличение, а не запрещает его`() {
        // 1800×1800 = 3,24 Мп. ×2 = 12,96 Мп — за бюджетом; ×1 остаётся.
        assertEquals(1, readingUpscale(1800, 1800))
        // 1500×1500 = 2,25 Мп. ×2 = 9 Мп — влезает, ×3 (20 Мп) нет.
        assertEquals(2, readingUpscale(1500, 1500))
    }

    @Test
    fun `бюджет считается по площади, а не по длинной стороне`() {
        // Та же длинная сторона 1800, что и в кадре выше, но узкой полосой: площадь мала, и
        // бюджет не мешает — увеличение остаётся тем, которое запросил размер.
        assertEquals(2, readingUpscale(1800, 200))
    }

    @Test
    fun `знаем буквы - судим по буквам, а не по кадру`() {
        // Мелкий кадр с крупными буквами (обрезанный заголовок): увеличивать незачем.
        assertEquals(1, readingUpscale(900, 300, textHeightPx = 48))
        // Тот же кадр без измеренных букв правило увеличило бы.
        assertEquals(3, readingUpscale(900, 300))
    }

    @Test
    fun `крупный кадр с мелким кеглем увеличивается, пока хватает памяти`() {
        // 1600×1200 = 1,92 Мп, буквы 12 px: до 30 просится ×3 (17 Мп — за бюджетом) → ×2.
        assertEquals(2, readingUpscale(1600, 1200, textHeightPx = 12))
        // А настоящее большое фото бюджет не пустит увеличивать вовсе.
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

    // --- плотность текста по уже прочитанному слою ---

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

    /**
     * Без деления на собственное увеличение слоя правило один раз увеличило бы кадр, а потом по
     * своему же результату решило бы, что увеличивать больше нечего, — и второе чтение того же
     * объекта поехало бы на неувеличенном кадре.
     */
    @Test
    fun `буквы слоя, читанного с увеличением, приводятся к неувеличенному кадру`() {
        // Оба движка видели слова одинаковыми — по 60 px в той копии, которую читали. Но у первой
        // копии эти 60 получились растяжением втрое, и в неувеличенном кадре буква вдвое ниже цели.
        val enlarged = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3)
        val asIs = FrameTransform(sample = 1, uprightWidth = 1000, uprightHeight = 750)
        assertEquals(20, typicalTextHeightPx(AtomLayer((1..12).map { word(it, 20f) }, transform = enlarged)))
        assertEquals(60, typicalTextHeightPx(AtomLayer((1..12).map { word(it, 60f) }, transform = asIs)))
    }

    @Test
    fun `у кадра боком высота меряется в выпрямленной копии, а не в сыром боксе`() {
        val transform = FrameTransform(sample = 1, rotationDegrees = 90, uprightWidth = 400, uprightHeight = 300)
        // Слово в выпрямленной копии: 40 px в ширину, 20 в высоту.
        val upright = Box(10f, 10f, 50f, 30f)
        val raw = transform.toRaw(upright)
        assertEquals(20, typicalTextHeightPx(AtomLayer((1..12).map { atom(it, raw) }, transform = transform)))
    }

    // --- шов увеличения ---

    /**
     * Ресайз — за интерфейсом, и порядок «решить → увеличить» проверяется без единого пикселя:
     * фейк здесь просто умножает числа.
     */
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
        // Тот же объект, а не копия: по этому вызывающий и узнаёт, что освобождать нечего.
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
