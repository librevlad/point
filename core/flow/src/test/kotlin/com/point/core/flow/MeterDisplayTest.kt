package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Поиск табло прибора без устройства (#262).
 *
 * **Что здесь настоящее и что нет.** Размеры кадров — настоящие: 3000×4000 и 2160×3840 сняты с
 * кадров 09/15/17 корпуса владельца (после EXIF-выпрямления), и рабочая копия здесь ровно та же,
 * что получится на телефоне. Пиксели — синтетические: фотографии владельца в репозиторий не
 * едут, а дословный кроп его счётчика — те же данные. Поэтому здесь проверяется то, что
 * проверяемо без снимка: геометрия, выбор области, пороги, отказ. Насколько поиск попадает в
 * настоящее табло, меряется прогоном по корпусу вне репозитория — и результат этого прогона
 * записан словами в `docs/CORPUS.md`, а не спрятан в зелёном тесте.
 */
class MeterDisplayTest {

    // ── рабочая копия кадров корпуса ────────────────────────────────────────────────────────

    /** 3000×4000 (кадры 09 и 15 корпуса после EXIF) при [METER_WORK_PX]. */
    private val waterMeterWork = 600 to 800

    /** 2160×3840 (кадр 17 корпуса после EXIF) при [METER_WORK_PX]. */
    private val powerMeterWork = 450 to 800

    @Test
    fun `рабочая копия кадра корпуса — та же, что посчитает телефон`() {
        assertEquals(waterMeterWork, workSize(3000, 4000))
        assertEquals(powerMeterWork, workSize(2160, 3840))
        // Кадр меньше рабочего размера не растягивается: увеличивать нечего.
        assertEquals(400 to 300, workSize(400, 300))
    }

    // ── поиск ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `строка одинаковых знаков находится и накрывается рамкой`() {
        val (w, h) = waterMeterWork
        val band = Band(left = 150, top = 380, digits = 8, digitW = 26, digitH = 44, pitch = 42)
        val found = findMeterDisplays(frameWith(w, h, band))

        assertTrue("табло не найдено", found.isNotEmpty())
        val best = found.first()
        assertEquals(0, best.angleDegrees)
        assertTrue("знаков ${best.digits}", best.digits in 4..12)
        val box = best.frame.fromRotated(best.cropRegion())
        assertTrue("рамка $box не накрыла ${band.box()}", covers(box, band.box()))
    }

    @Test
    fun `наклонное табло находится вместе со своим наклоном`() {
        val (w, h) = powerMeterWork
        // 20° — примерно так стоит барабан на кадре, снятом с рук от бедра.
        val band = Band(left = 60, top = 300, digits = 7, digitW = 24, digitH = 40, pitch = 40, slopeDegrees = 20)
        val found = findMeterDisplays(frameWith(w, h, band))

        assertTrue("наклонное табло не найдено", found.isNotEmpty())
        val angle = found.first().angleDegrees
        assertTrue("угол $angle не похож на 20°", abs(abs(angle) - 20) <= 5)
    }

    @Test
    fun `ровный фон — честная пустота, а не выдуманное табло`() {
        val (w, h) = waterMeterWork
        assertTrue(findMeterDisplays(GrayFrame(w, h, IntArray(w * h) { 190 })).isEmpty())
    }

    @Test
    fun `одинокое пятно табло не образует`() {
        val (w, h) = waterMeterWork
        val band = Band(left = 200, top = 400, digits = 1, digitW = 26, digitH = 44, pitch = 42)
        assertTrue(findMeterDisplays(frameWith(w, h, band)).isEmpty())
    }

    @Test
    fun `слово табло не считается — знаки разной ширины`() {
        val (w, h) = waterMeterWork
        // Ширины 12, 40, 14, 38, 13, 41 — разброс, какого у барабана не бывает.
        val letters = listOf(12, 40, 14, 38, 13, 41)
        val luma = IntArray(w * h) { 205 }
        var x = 120
        letters.forEach { width ->
            drawGlyph(luma, w, x, 400, width, 44, 35)
            x += width + 18
        }
        val found = findMeterDisplays(GrayFrame(w, h, luma))
        val onBand = found.filter { overlapsRow(it.frame.fromRotated(it.region), top = 400, bottom = 444) }
        assertTrue("строка с разными ширинами принята за табло: $onBand", onBand.isEmpty())
    }

    @Test
    fun `мест отдаётся не больше, чем просили`() {
        val (w, h) = waterMeterWork
        val luma = IntArray(w * h) { 205 }
        // Четыре одинаковых строки — все похожи на табло; выбирать за человека мы не беремся,
        // но и заваливать движок десятком проходов тоже.
        listOf(120, 300, 480, 660).forEach { top ->
            var x = 100
            repeat(7) {
                drawGlyph(luma, w, x, top, 24, 40, 35)
                x += 40
            }
        }
        assertTrue(findMeterDisplays(GrayFrame(w, h, luma), limit = 2).size <= 2)
        assertTrue(findMeterDisplays(GrayFrame(w, h, luma)).size <= METER_MAX_CANDIDATES)
    }

    // ── геометрия ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `поворот кадра корпуса на прямой угол меняет стороны местами`() {
        val landscape = FrameRotation(90, 4000, 3000)
        assertEquals(3000, landscape.rotatedWidth)
        assertEquals(4000, landscape.rotatedHeight)
        val untouched = FrameRotation(0, 3840, 2160)
        assertEquals(3840, untouched.rotatedWidth)
        assertEquals(2160, untouched.rotatedHeight)
    }

    @Test
    fun `наклонный кадр растёт по обеим сторонам`() {
        val tilted = FrameRotation(20, 3000, 4000)
        assertTrue(tilted.rotatedWidth > 3000)
        assertTrue(tilted.rotatedHeight > 4000)
        // Площадь описанного прямоугольника не может быть меньше исходной.
        assertTrue(tilted.rotatedWidth.toLong() * tilted.rotatedHeight >= 3000L * 4000)
    }

    @Test
    fun `дорога в повёрнутую копию и обратно возвращает ту же точку`() {
        val rotation = FrameRotation(35, 3000, 4000)
        val point = 1234f to 2345f
        val x = rotation.toRotatedX(point.first, point.second)
        val y = rotation.toRotatedY(point.first, point.second)
        assertEquals(point.first, rotation.fromRotatedX(x, y), 0.01f)
        assertEquals(point.second, rotation.fromRotatedY(x, y), 0.01f)
    }

    @Test
    fun `поле вокруг знаков считается в высотах знака и упирается в край кадра`() {
        val rotation = FrameRotation(0, 600, 800)
        val candidate = MeterDisplayCandidate(
            angleDegrees = 0,
            region = Box(10f, 400f, 300f, 440f),
            digits = 7,
            digitHeight = 40f,
            darkDigits = true,
            score = 1f,
            frame = rotation,
        )
        val crop = candidate.cropRegion()
        assertEquals(0f, crop.left, 0.01f) // 10 - 40 ушло бы за край — обрезано
        assertEquals(400f - 16f, crop.top, 0.01f)
        assertEquals(300f + 40f, crop.right, 0.01f)
        assertEquals(440f + 16f, crop.bottom, 0.01f)
    }

    @Test
    fun `рамка переносится в масштаб полноразмерного снимка`() {
        val work = FrameRotation(0, 600, 800)
        val full = FrameRotation(0, 1536, 2048) // 3000×4000, декодированный с потолком 2048
        val candidate = MeterDisplayCandidate(0, Box(100f, 400f, 300f, 440f), 7, 40f, true, 1f, work)
        val scaled = candidate.cropRegionIn(full)
        val k = 1536f / 600f
        assertEquals(candidate.cropRegion().left * k, scaled.left, 0.5f)
        assertEquals(candidate.cropRegion().right * k, scaled.right, 0.5f)
        assertTrue(scaled.right <= full.rotatedWidth.toFloat())
        assertTrue(scaled.bottom <= full.rotatedHeight.toFloat())
    }

    // ── пороги ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `увеличение целое и только вверх`() {
        assertEquals(1, meterUpscale(80f))
        assertEquals(1, meterUpscale(60f))
        assertEquals(2, meterUpscale(31f))
        assertEquals(3, meterUpscale(21f))
        assertEquals(4, meterUpscale(9f))
        assertEquals(4, meterUpscale(1f)) // предел: интерполяция не рисует того, чего нет
        assertEquals(1, meterUpscale(0f))
    }

    @Test
    fun `прочитанным считается то, где цифр не меньше, чем требует правило показания`() {
        assertEquals("00001154", meterDigitsRead("00001154"))
        assertEquals("0208425", meterDigitsRead("0 2 0 8 4 2 5\n"))
        assertNull(meterDigitsRead("12"))
        assertNull(meterDigitsRead(""))
        assertNull(meterDigitsRead("   "))
    }

    @Test
    fun `контраст приводит обе полярности к чёрному по белому`() {
        val dark = frameWith(120, 60, Band(left = 20, top = 15, digits = 4, digitW = 10, digitH = 20, pitch = 20))
        val light = GrayFrame(120, 60, IntArray(dark.luma.size) { 255 - dark.luma[it] })

        val fromDark = meterInk(dark, digitHeightPx = 20f, darkDigits = true)
        val fromLight = meterInk(light, digitHeightPx = 20f, darkDigits = false)

        assertTrue(fromDark.luma.all { it == 0 || it == 255 })
        assertTrue(fromLight.luma.all { it == 0 || it == 255 })
        // Негатив того же табло даёт тот же чёрно-белый вход движку, а не его негатив.
        assertEquals(fromDark.luma.toList(), fromLight.luma.toList())
    }

    // ── итог чтения ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `не нашли и не прочитали — разные новости`() {
        assertTrue(MeterReadout.NOTHING.nothingFound)
        assertTrue(!MeterReadout.NOTHING.foundButUnread)

        val searched = MeterReadout(emptyList(), candidates = 3)
        assertTrue(searched.foundButUnread)
        assertTrue(!searched.nothingFound)

        val read = MeterReadout(listOf(MeterDisplayReading("00001154", Box(0f, 0f, 1f, 1f), 0)), candidates = 3)
        assertTrue(!read.nothingFound)
        assertTrue(!read.foundButUnread)
    }

    @Test
    fun `чтение мест рассказывает, какое из скольких идёт сейчас`() {
        // Число мест известно точно до первого прохода движка — кандидаты уже отобраны, поэтому
        // «из скольких» здесь правда, а не обещание (#288).
        assertEquals("Читаю цифры — место 1 из 3", meterPlaceStage(0, 3))
        assertEquals("Читаю цифры — место 3 из 3", meterPlaceStage(2, 3))
    }

    @Test
    fun `единственное место — счёта нет, он не сообщал бы ничего`() {
        assertEquals("Читаю цифры на табло", meterPlaceStage(0, 1))
    }

    // ── помощники ───────────────────────────────────────────────────────────────────────────

    /** Размер рабочей копии: длинная сторона к [METER_WORK_PX], мелкое не растягивается. */
    private fun workSize(width: Int, height: Int): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= METER_WORK_PX) return width to height
        val k = METER_WORK_PX.toFloat() / long
        return (width * k).roundToInt() to (height * k).roundToInt()
    }

    private class Band(
        val left: Int,
        val top: Int,
        val digits: Int,
        val digitW: Int,
        val digitH: Int,
        val pitch: Int,
        val slopeDegrees: Int = 0,
    ) {
        fun box(): Box = Box(
            left.toFloat(),
            top.toFloat(),
            (left + (digits - 1) * pitch + digitW).toFloat(),
            (top + digitH + ((digits - 1) * pitch * tan(slopeDegrees * Math.PI / 180)).toInt()).toFloat(),
        )
    }

    /** Светлый щиток с тёмной строкой одинаковых знаков — тем самым, что ищет [findMeterDisplays]. */
    private fun frameWith(width: Int, height: Int, band: Band): GrayFrame {
        val luma = IntArray(width * height) { 205 }
        repeat(band.digits) { i ->
            val x = band.left + i * band.pitch
            val y = band.top + (i * band.pitch * tan(band.slopeDegrees * Math.PI / 180)).toInt()
            drawGlyph(luma, width, x, y, band.digitW, band.digitH, 35)
        }
        return GrayFrame(width, height, luma)
    }

    /** Знак — рамка с дыркой: сплошной прямоугольник на цифру не похож ничем. */
    private fun drawGlyph(luma: IntArray, width: Int, x: Int, y: Int, w: Int, h: Int, ink: Int) {
        val stroke = maxOf(2, w / 5)
        for (dy in 0 until h) {
            for (dx in 0 until w) {
                val edge = dx < stroke || dx >= w - stroke || dy < stroke || dy >= h - stroke
                if (!edge) continue
                val px = x + dx
                val py = y + dy
                if (px in 0 until width && py * width + px in luma.indices) luma[py * width + px] = ink
            }
        }
    }

    private fun covers(box: Box, target: Box): Boolean =
        box.left <= target.left + 2 && box.top <= target.top + 2 &&
            box.right >= target.right - 2 && box.bottom >= target.bottom - 2

    private fun overlapsRow(box: Box, top: Int, bottom: Int): Boolean =
        box.bottom > top && box.top < bottom
}
