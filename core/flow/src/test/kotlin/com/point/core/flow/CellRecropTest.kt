package com.point.core.flow

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перечит спорной ячейки кропом (#346): третий голос входит в голосование ячейки, а не заменяет
 * его; ячейка без однозначного адреса на кадре не перечитывается; общий срок не роняет свод —
 * не успевшие остаются спором и дропдауном, как были.
 */
class CellRecropTest {

    private var n = 0
    private fun atom(text: String, x: Float, y: Float) =
        Atom("a${n++}", text, Box(x, y, x + 80f, y + 20f))

    /** Два ряда ведомости на кадре: артикул, название, количество. */
    private fun sheet() = AtomLayer(
        listOf(
            atom("11004", 0f, 100f), atom("Гречка", 100f, 100f), atom("0,120", 300f, 100f),
            atom("11006", 0f, 200f), atom("Рис", 100f, 200f), atom("0,500", 300f, 200f),
        ),
    )

    /** Свод двух чтений: количество гречки спорно — «0,120» против «0,125». */
    private fun voted() = Consensus(
        rows = listOf(listOf("11004", "Гречка", "0,120⚠"), listOf("11006", "Рис", "0,500")),
        candidates = mapOf((0 to 2) to listOf("0,120", "0,125")),
        sources = 2,
    )

    @Test
    fun `согласный третий голос снимает спор — ячейка чистая, дропдауна нет`() = runTest {
        val out = recropDisputed(voted(), sheet(), timeoutMs = 5_000) { "0,120" }

        assertEquals("0,120", out.rows[0][2])
        assertTrue(out.candidates.isEmpty())
    }

    @Test
    fun `большинство может передумать значение — два голоса против одного`() = runTest {
        val out = recropDisputed(voted(), sheet(), timeoutMs = 5_000) { "0,125" }

        assertEquals("0,125", out.rows[0][2])
        assertTrue(out.candidates.isEmpty())
    }

    @Test
    fun `несогласный третий голос спор не решает — он встаёт вариантом рядом`() = runTest {
        val out = recropDisputed(voted(), sheet(), timeoutMs = 5_000) { "0,999" }

        assertEquals("0,120⚠", out.rows[0][2])
        assertEquals(listOf("0,120", "0,125", "0,999"), out.candidates[0 to 2])
    }

    @Test
    fun `не успевший в общий срок перечит не роняет свод — спор остаётся как был`() = runTest {
        val out = recropDisputed(voted(), sheet(), timeoutMs = 1_000) { awaitCancellation() }

        assertEquals(voted().rows, out.rows)
        assertEquals(voted().candidates, out.candidates)
    }

    @Test
    fun `успевшие голоса засчитаны, не успевшие остаются спором`() = runTest {
        val two = Consensus(
            rows = listOf(listOf("11004", "Гречка", "0,120⚠"), listOf("11006", "Рис⚠", "0,500")),
            candidates = mapOf((0 to 2) to listOf("0,120", "0,125"), (1 to 1) to listOf("Рис", "Лис")),
        )

        val out = recropDisputed(two, sheet(), timeoutMs = 1_000) { q ->
            if (q.cell == 0 to 2) "0,120" else awaitCancellation()
        }

        assertEquals("0,120", out.rows[0][2])
        assertEquals(setOf(1 to 1), out.candidates.keys)
    }

    @Test
    fun `отказ маршрута на одной ячейке не трогает ни соседей, ни свод`() = runTest {
        val two = Consensus(
            rows = listOf(listOf("11004", "Гречка", "0,120⚠"), listOf("11006", "Рис⚠", "0,500")),
            candidates = mapOf((0 to 2) to listOf("0,120", "0,125"), (1 to 1) to listOf("Рис", "Лис")),
        )

        val out = recropDisputed(two, sheet(), timeoutMs = 5_000) { q ->
            if (q.cell == 0 to 2) "0,120" else error("HTTP 429 quota")
        }

        assertEquals("0,120", out.rows[0][2])
        assertEquals(listOf("Рис", "Лис"), out.candidates[1 to 1])
    }

    @Test
    fun `строка, которой нет на кадре, не перечитывается — спор остаётся человеку`() = runTest {
        val ghost = Consensus(
            rows = listOf(listOf("11004", "Гречка", "0,120"), listOf("Итого", "2400⚠")),
            candidates = mapOf((1 to 1) to listOf("2400", "2100")),
        )
        var asked = 0

        val out = recropDisputed(ghost, sheet(), timeoutMs = 5_000) { asked++; "2400" }

        assertEquals("приложить соседнюю строку хуже, чем ничего", 0, asked)
        assertEquals("2400⚠", out.rows[1][1])
        assertEquals(listOf("2400", "2100"), out.candidates[1 to 1])
    }

    @Test
    fun `двусмысленное место — не адрес, два одинаковых ряда на кадре гасят перечит`() = runTest {
        val twins = AtomLayer(
            listOf(
                atom("11004", 0f, 100f), atom("Гречка", 100f, 100f), atom("0,120", 300f, 100f),
                atom("11004", 0f, 300f), atom("Гречка", 100f, 300f), atom("0,120", 300f, 300f),
            ),
        )
        var asked = 0

        val out = recropDisputed(voted(), twins, timeoutMs = 5_000) { asked++; "0,120" }

        assertEquals(0, asked)
        assertEquals("0,120⚠", out.rows[0][2])
    }

    @Test
    fun `спорное значение само себе не адрес — чужая строка с тем же числом не перечитывается`() = runTest {
        // Строку спора движок не прочитал вовсе, а «0,120» на листе есть — в ЧУЖОЙ строке.
        // Если искать строку по спорному значению, именно ошибочный свод находит чужое место
        // «однозначно»: своей строки с этим числом на кадре нет. Модель тогда честно читает
        // не ту ячейку, голосом 2 из 3 подтверждает ошибку и снимает пометку с дропдауном.
        val elsewhere = AtomLayer(
            listOf(
                atom("11006", 0f, 200f), atom("Рис", 100f, 200f), atom("0,500", 300f, 200f),
                atom("Крупа", 100f, 300f), atom("0,120", 300f, 300f),
            ),
        )
        val blind = Consensus(
            rows = listOf(listOf("Гречка", "0,120⚠"), listOf("11006", "Рис", "0,500")),
            candidates = mapOf((0 to 1) to listOf("0,120", "0,125")),
            sources = 2,
        )
        var asked = 0

        val out = recropDisputed(blind, elsewhere, timeoutMs = 5_000) { asked++; "0,120" }

        assertEquals("адрес, собранный из спорного значения, — не адрес", 0, asked)
        assertEquals("0,120⚠", out.rows[0][1])
        assertEquals(listOf("0,120", "0,125"), out.candidates[0 to 1])
    }

    @Test
    fun `соседняя спорная ячейка — тоже не адрес, строку узнают бесспорные ячейки`() = runTest {
        // В строке спорят две ячейки; значение второй («1,375») нашлось бы в чужой строке листа.
        val elsewhere = AtomLayer(
            listOf(atom("11006", 0f, 200f), atom("Рис", 100f, 200f), atom("1,375", 300f, 200f)),
        )
        val pair = Consensus(
            rows = listOf(listOf("Гречка", "0,120⚠", "1,375⚠")),
            candidates = mapOf(
                (0 to 1) to listOf("0,120", "0,125"),
                (0 to 2) to listOf("1,375", "1,875"),
            ),
            sources = 2,
        )
        var asked = 0

        val out = recropDisputed(pair, elsewhere, timeoutMs = 5_000) { asked++; "0,120" }

        assertEquals(0, asked)
        assertEquals(pair.candidates, out.candidates)
    }

    @Test
    fun `спорных ячеек больше дюжины — перечит не начинается вовсе`() = runTest {
        val wall = Consensus(
            rows = (0 until 13).map { listOf("строка$it⚠") },
            candidates = (0 until 13).associate { (it to 0) to listOf("а$it", "б$it") },
        )
        var asked = 0

        val out = recropDisputed(wall, sheet(), timeoutMs = 5_000) { asked++; "а0" }

        assertEquals(0, asked)
        assertEquals(wall.candidates, out.candidates)
    }

    @Test
    fun `многословный ответ и голое ⚠ голосом не становятся`() = runTest {
        val prose = recropDisputed(voted(), sheet(), timeoutMs = 5_000) { "Вот что я вижу.\nЭто 0,120" }
        assertEquals("0,120⚠", prose.rows[0][2])
        assertEquals(listOf("0,120", "0,125"), prose.candidates[0 to 2])

        val unread = recropDisputed(voted(), sheet(), timeoutMs = 5_000) { "⚠" }
        assertEquals("0,120⚠", unread.rows[0][2])
        assertEquals(listOf("0,120", "0,125"), unread.candidates[0 to 2])
    }

    @Test
    fun `ответ в ограждении кода читается, формат не решает судьбу голоса`() = runTest {
        val out = recropDisputed(voted(), sheet(), timeoutMs = 5_000) { "```\n0,120\n```" }

        assertEquals("0,120", out.rows[0][2])
        assertTrue(out.candidates.isEmpty())
    }

    @Test
    fun `стадия называет число перечитываемых ячеек`() = runTest {
        val heard = mutableListOf<String>()

        withContext(ActionProgress { heard += it }) {
            recropDisputed(voted(), sheet(), timeoutMs = 5_000) { "0,120" }
        }

        assertEquals(listOf("Переспрашиваю 1 спорную ячейку"), heard)
    }

    @Test
    fun `когда переспрашивать нечего, стадия молчит`() = runTest {
        val heard = mutableListOf<String>()

        withContext(ActionProgress { heard += it }) {
            recropDisputed(Consensus(voted().rows, emptyMap()), sheet(), timeoutMs = 5_000) { "x" }
        }

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `перечит не теряет счёт чтений свода`() = runTest {
        val out = recropDisputed(voted(), sheet(), timeoutMs = 5_000) { "0,120" }

        assertEquals(2, out.sources)
    }
}
