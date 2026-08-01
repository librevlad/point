package com.point.core.flow

import org.junit.Test

/** ВРЕМЕННЫЙ щуп ревью: порог 50% в rowsSimilar. Удаляется после прогона. */
class TmpZzRowsSimilarHalfTest {

    private fun show(name: String, c: Consensus) {
        println("=== $name")
        c.rows.forEachIndexed { i, r -> println("  row[$i] = $r") }
        println("  candidates = " + c.candidates.entries.joinToString { "(${it.key.first},${it.key.second})=${it.value}" })
    }

    @Test
    fun `two column list with a repeated column, one model skipped a row`() {
        val full = listOf(listOf("Гречка", "1"), listOf("Пшено", "1"), listOf("Овес", "1"))
        val skipped = listOf(listOf("Гречка", "1"), listOf("Овес", "1"))
        show("A: повторяющийся 2-й столбец, пропущено «Пшено»", reconcile(listOf(full, skipped)))
    }

    @Test
    fun `control - same skip but the second column differs per row`() {
        val full = listOf(listOf("Гречка", "2"), listOf("Пшено", "3"), listOf("Овес", "4"))
        val skipped = listOf(listOf("Гречка", "2"), listOf("Овес", "4"))
        show("B: КОНТРОЛЬ, различающийся 2-й столбец", reconcile(listOf(full, skipped)))
    }

    @Test
    fun `four column invoice where unit and date repeat`() {
        val full = listOf(
            listOf("Гречка", "шт", "10.05.2024", "25"),
            listOf("Пшено", "шт", "10.05.2024", "30"),
            listOf("Овес", "шт", "10.05.2024", "40"),
        )
        val skipped = listOf(
            listOf("Гречка", "шт", "10.05.2024", "25"),
            listOf("Овес", "шт", "10.05.2024", "40"),
        )
        show("C: 4 столбца, повторяются «шт» и дата", reconcile(listOf(full, skipped)))
    }

    @Test
    fun `raw predicate probe via reconcile of single pairs`() {
        // одна строка против одной: если считаются «похожими», слот один и появится ⚠
        val probes = listOf(
            listOf(listOf("Пшено", "1")) to listOf(listOf("Овес", "1")),
            listOf(listOf("Пшено", "3")) to listOf(listOf("Овес", "4")),
            listOf(listOf("Гречка", "шт", "10.05.2024", "25")) to listOf(listOf("Пшено", "шт", "10.05.2024", "30")),
        )
        probes.forEach { (a, b) ->
            val c = reconcile(listOf(a, b))
            println("probe ${a[0]} vs ${b[0]} -> слотов=${c.rows.size}, rows=${c.rows}")
        }
    }
}
