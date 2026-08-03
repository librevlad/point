package com.point.core.flow

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чтение под пределом времени (#262): живой прогон корпуса поймал чтения, которые не кончаются
 * (12-мегапиксельный кадр × четыре полных прохода движка, ни одного колпака), и снаружи это было
 * неотличимо от «ещё думает». Здесь движок — фейковый и медленный ровно настолько, насколько
 * скажет тест: предел обязан срабатывать, результат — оставаться частичным, лог — честным.
 */
class ReadingBudgetTest {

    private class FakeClock(var now: Long = 0L) : OcrClock {
        override fun nowMs(): Long = now
    }

    /**
     * Фейковый медленный движок: чтение «стоит» столько-то миллисекунд. Не уложился в колпак —
     * съедает ровно колпак и приходит отрезанным с пустым слоем, как настоящий (после отмены
     * Tesseract результат не читается). Запоминает, какие углы у него просили.
     */
    private class SlowEngine(
        private val clock: FakeClock,
        private val costMs: Long,
        private val result: (Int) -> AtomLayer,
    ) : (Int, Long) -> CappedRead {
        val calls = mutableListOf<Int>()
        override fun invoke(angleDegrees: Int, capMs: Long): CappedRead {
            calls += angleDegrees
            return if (costMs > capMs) {
                clock.now += capMs
                CappedRead(AtomLayer(emptyList()), cut = true)
            } else {
                clock.now += costMs
                CappedRead(result(angleDegrees), cut = false)
            }
        }
    }

    private fun layerOf(vararg words: Pair<String, Float>) = AtomLayer(
        words.mapIndexed { i, (text, conf) ->
            Atom("w$i", text, Box(0f, i * 20f, 100f, i * 20f + 18f), confidence = conf)
        },
    )

    /** Та же страница, что в OrientationTest: заведомо не «слабое чтение». */
    private fun goodPage() = layerOf(
        "Трек-номер" to 0.95f, "20" to 0.96f, "4514" to 0.95f, "9154" to 0.96f, "9395" to 0.95f,
        "Відправник" to 0.93f, "Іваненко" to 0.94f, "Іван" to 0.92f,
    )

    /** Мусор боковых строк — то, что движок читает с повёрнутого кадра. */
    private fun garbage() = layerOf("|" to 0.2f, "l~" to 0.15f, "//" to 0.1f, "т" to 0.3f)

    @Test
    fun `хорошее чтение уложилось — пробы не запускаются, пометки нет`() = runTest {
        val clock = FakeClock()
        val page = goodPage()
        val full = SlowEngine(clock, 5_000) { page }
        val probe = SlowEngine(clock, 1_000) { page }

        val out = readWithBudget(ReadingBudget(180_000, clock), full, probe)

        assertSame(page, out.layer)
        assertNull(out.layer.incomplete)
        assertEquals(0, out.angleDegrees)
        assertEquals(listOf(0), full.calls)
        assertTrue(probe.calls.isEmpty())
    }

    @Test
    fun `вечное базовое чтение отрезается бюджетом — спасает упрощённое`() = runTest {
        val clock = FakeClock()
        val full = SlowEngine(clock, 400_000) { goodPage() } // «вечность»
        val probe = SlowEngine(clock, 1_000) { goodPage() }

        val out = readWithBudget(ReadingBudget(180_000, clock), full, probe)

        // Полное чтение не уложилось — но припасённое время спасло страницу: читается
        // уменьшенная копия, и человек получает текст вместо пустоты (прогон примеров
        // 03.08.2026: кадру 04 не хватало и полного бюджета — 0 слов на 181-й секунде).
        assertTrue(out.layer.atoms.isNotEmpty())
        assertEquals(INCOMPLETE_TIMEOUT, out.layer.incomplete)
        assertEquals(0, out.angleDegrees)
        // Повороты при этом не перебираются: сравнивать их не с чем.
        assertEquals(listOf(0), probe.calls)
        // Съеден весь бюджет и ни минутой больше: вечность держит он, а не половинный колпак.
        // Половина стоила живых чтений — страница, которой нужно 120 с из 180, возвращала ноль
        // слов вместо текста (прогон примеров 03.08.2026).
        // 150 с полного чтения (бюджет минус припасённое) плюс 1 с упрощённого: вечность
        // держит бюджет, а запас тратится только когда полное чтение не уложилось.
        assertEquals(151_000L, clock.now)
    }

    @Test
    fun `перевёрнутая страница — проба выигрывает и дочитывается полным кадром`() = runTest {
        val clock = FakeClock()
        val fullGood = goodPage()
        val probeGood = goodPage()
        val full = SlowEngine(clock, 10_000) { angle -> if (angle == 180) fullGood else garbage() }
        val probe = SlowEngine(clock, 2_000) { angle -> if (angle == 180) probeGood else garbage() }

        val out = readWithBudget(ReadingBudget(180_000, clock), full, probe)

        assertSame(fullGood, out.layer)
        assertNull(out.layer.incomplete)
        assertEquals(180, out.angleDegrees)
        assertEquals(listOf(0, 180), full.calls)
        assertEquals(listOf(90, 180, 270), probe.calls)
    }

    @Test
    fun `пробы не уложились в предел — итог базовый, причина названа`() = runTest {
        val clock = FakeClock()
        val base = garbage()
        val full = SlowEngine(clock, 80_000) { base }
        val probe = SlowEngine(clock, 40_000) { garbage() }

        val out = readWithBudget(ReadingBudget(180_000, clock), full, probe)

        // 80 + 40 + 40 = 160 из 180; третья проба отрезана на остатке в 20 секунд.
        assertEquals(180_000L, clock.now)
        assertEquals(base.atoms, out.layer.atoms)
        assertEquals(INCOMPLETE_TIMEOUT, out.layer.incomplete)
        assertEquals(0, out.angleDegrees)
        assertEquals(listOf(90, 180, 270), probe.calls)
    }

    @Test
    fun `победителю не хватило времени на дочитку — итогом становится слой пробы`() = runTest {
        val clock = FakeClock()
        val probeGood = goodPage()
        val full = SlowEngine(clock, 90_000) { garbage() }
        val probe = SlowEngine(clock, 30_000) { angle -> if (angle == 180) probeGood else garbage() }

        val out = readWithBudget(ReadingBudget(180_000, clock), full, probe)

        // Пробы съели остаток: дочитывать победивший доворот нечем — отдаётся сама проба,
        // адресно честная (её transform помнит масштаб копии), с причиной.
        assertEquals(probeGood.atoms, out.layer.atoms)
        assertEquals(INCOMPLETE_TIMEOUT, out.layer.incomplete)
        assertEquals(180, out.angleDegrees)
        assertEquals(listOf(0), full.calls) // дочитки не было
    }

    @Test
    fun `дочитывание отрезано — фолбэк на слой пробы, а не пустота`() = runTest {
        val clock = FakeClock()
        val probeGood = goodPage()
        val full = object : (Int, Long) -> CappedRead {
            val calls = mutableListOf<Int>()
            override fun invoke(angleDegrees: Int, capMs: Long): CappedRead {
                calls += angleDegrees
                // Базовое чтение быстрое и мусорное, дочитка — вечная и отрезается.
                return if (angleDegrees == 0) {
                    clock.now += 60_000
                    CappedRead(garbage(), cut = false)
                } else {
                    clock.now += capMs
                    CappedRead(AtomLayer(emptyList()), cut = true)
                }
            }
        }
        val probe = SlowEngine(clock, 20_000) { angle -> if (angle == 180) probeGood else garbage() }

        val out = readWithBudget(ReadingBudget(180_000, clock), full, probe)

        assertEquals(probeGood.atoms, out.layer.atoms)
        assertEquals(INCOMPLETE_TIMEOUT, out.layer.incomplete)
        assertEquals(180, out.angleDegrees)
        assertEquals(listOf(0, 180), full.calls)
    }

    /** Что действие рассказало о себе, пока шло чтение (#288). */
    private suspend fun stagesHeard(action: suspend () -> Unit): List<String> {
        val heard = mutableListOf<String>()
        withContext(ActionProgress { heard += it }) { action() }
        return heard
    }

    @Test
    fun `перебор поворотов рассказывает о себе — по попытке за раз и в порядке`() = runTest {
        val clock = FakeClock()
        val fullGood = goodPage()
        val full = SlowEngine(clock, 10_000) { angle -> if (angle == 180) fullGood else garbage() }
        val probe = SlowEngine(clock, 2_000) { angle -> if (angle == 180) goodPage() else garbage() }

        val heard = stagesHeard { readWithBudget(ReadingBudget(180_000, clock), full, probe) }

        assertEquals(
            listOf(
                "Пробую повернуть страницу — 1 из 3",
                "Пробую повернуть страницу — 2 из 3",
                "Пробую повернуть страницу — 3 из 3",
                "Нашёл, как лежит страница — перечитываю",
            ),
            heard,
        )
    }

    @Test
    fun `страница прочиталась с первого раза — сказать нечего`() = runTest {
        val clock = FakeClock()
        val page = goodPage()
        val full = SlowEngine(clock, 5_000) { page }
        val probe = SlowEngine(clock, 1_000) { page }

        // Работы сверх базового чтения не было — и слов о ней нет: стадия у ненаступившего шага
        // и есть та имитация статуса, против которой сделан весь #288.
        assertTrue(stagesHeard { readWithBudget(ReadingBudget(180_000, clock), full, probe) }.isEmpty())
    }

    @Test
    fun `отрезанное базовое чтение зовёт упрощённое, а повороты не перебирает`() = runTest {
        val clock = FakeClock()
        val full = SlowEngine(clock, 400_000) { goodPage() }
        val probe = SlowEngine(clock, 1_000) { goodPage() }

        val heard = stagesHeard { readWithBudget(ReadingBudget(180_000, clock), full, probe) }

        // Единственная сказанная фраза — про упрощённое чтение: повороты перебирать не на чем,
        // а молчать, ухудшив чтение, нельзя.
        assertEquals(listOf(FALLBACK_STAGE), heard)
    }

    @Test
    fun `на дочитывание не осталось времени — обещания перечитать нет`() = runTest {
        val clock = FakeClock()
        val full = SlowEngine(clock, 90_000) { garbage() }
        val probe = SlowEngine(clock, 30_000) { angle -> if (angle == 180) goodPage() else garbage() }

        val heard = stagesHeard { readWithBudget(ReadingBudget(180_000, clock), full, probe) }

        // Пробы съели остаток: дочитки не будет, и слова о ней быть не должно.
        assertEquals(
            listOf(
                "Пробую повернуть страницу — 1 из 3",
                "Пробую повернуть страницу — 2 из 3",
                "Пробую повернуть страницу — 3 из 3",
            ),
            heard,
        )
    }

    @Test
    fun `счёт попыток человеку — с единицы, а не с индекса`() {
        assertEquals("Пробую повернуть страницу — 1 из 3", orientationProbeStage(0, 3))
        assertEquals("Пробую повернуть страницу — 3 из 3", orientationProbeStage(2, 3))
    }

    @Test
    fun `строка OCR done есть всегда — и с причиной, когда чтение не дочитано`() {
        val whole = ocrDoneLine(goodPage(), 5_000)
        assertTrue(whole.startsWith("OCR done: "))
        assertFalse(whole.contains("timeout"))

        val cut = ocrDoneLine(AtomLayer(emptyList(), incomplete = INCOMPLETE_TIMEOUT), 180_000)
        assertEquals("OCR done: 0 words, 0 chars, 180000 ms (timeout)", cut)

        val broken = ocrDoneLine(AtomLayer(emptyList(), incomplete = "decode failed"), 12)
        assertEquals("OCR done: 0 words, 0 chars, 12 ms (decode failed)", broken)
    }

    @Test
    fun `бюджет меряется часами за швом и не уходит в минус`() {
        val clock = FakeClock(1_000)
        val budget = ReadingBudget(10_000, clock)

        assertEquals(0L, budget.spentMs())
        assertEquals(10_000L, budget.leftMs())
        // Базовому чтению — весь остаток: страница, которой нужно больше половины бюджета,
        // на половине возвращала НОЛЬ слов (прогон примеров 03.08.2026: было 385 и 329, стало
        // пусто). Пробы поворотов — уточнение угла, базовое чтение — сам продукт.
        // Из бюджета вычтен запас на страховочное чтение уменьшенной копии.
        assertEquals(10_000L - 10_000L / 6, budget.baseCapMs())

        clock.now = 4_000
        assertEquals(3_000L, budget.spentMs())
        assertEquals(7_000L, budget.leftMs())
        assertEquals(7_000L - 10_000L / 6, budget.baseCapMs())

        clock.now = 100_000
        assertEquals(0L, budget.leftMs())
        assertEquals(0L, budget.baseCapMs())
    }
}
