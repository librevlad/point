package com.point.core.flow

import org.junit.Test

class TmpSkepticRowsSimilarTest {

    private fun dump(label: String, c: Consensus) {
        println("=== $label")
        c.rows.forEachIndexed { i, r -> println("  row$i = $r") }
        println("  candidates = " + c.candidates.entries.joinToString { "${it.key}=${it.value}" })
    }

    @Test
    fun `repro shopping list repeated qty column`() {
        val a = listOf(listOf("Гречка", "1"), listOf("Пшено", "1"), listOf("Овес", "1"))
        val b = listOf(listOf("Гречка", "1"), listOf("Овес", "1"))
        dump("A) repeated qty col, second model skipped Пшено", reconcile(listOf(a, b)))

        // control: distinct second column
        val a2 = listOf(listOf("Гречка", "2"), listOf("Пшено", "3"), listOf("Овес", "4"))
        val b2 = listOf(listOf("Гречка", "2"), listOf("Овес", "4"))
        dump("B) control, distinct second col", reconcile(listOf(a2, b2)))

        // three-column invoice-ish with two repeating columns
        val a3 = listOf(
            listOf("Гречка", "1", "шт"),
            listOf("Пшено", "1", "шт"),
            listOf("Овес", "1", "шт"),
        )
        val b3 = listOf(listOf("Гречка", "1", "шт"), listOf("Овес", "1", "шт"))
        dump("C) 3 cols, 2 repeat", reconcile(listOf(a3, b3)))

        // single column, no repetition at all — how does a pure name list behave?
        val a4 = listOf(listOf("Гречка"), listOf("Пшено"), listOf("Овес"))
        val b4 = listOf(listOf("Гречка"), listOf("Овес"))
        dump("D) single col names", reconcile(listOf(a4, b4)))

        // with a header row present (the realistic xlsx shape)
        val a5 = listOf(
            listOf("Товар", "К-сть"),
            listOf("Гречка", "1"),
            listOf("Пшено", "1"),
            listOf("Овес", "1"),
        )
        val b5 = listOf(listOf("Товар", "К-сть"), listOf("Гречка", "1"), listOf("Овес", "1"))
        dump("E) header + repeated qty", reconcile(listOf(a5, b5)))

        // three models, one skipped (CONSENSUS_N could be 2, but check 3 anyway)
        val c6 = listOf(listOf("Гречка", "1"), listOf("Пшено", "1"), listOf("Овес", "1"))
        dump("F) 3 reads, one skipped", reconcile(listOf(a, b, c6)))
    }
}
