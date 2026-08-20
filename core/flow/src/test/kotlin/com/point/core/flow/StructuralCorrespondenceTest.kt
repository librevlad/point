package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Каноническое соответствие структурных узлов (#1176, STRUCTURAL NODE CORRESPONDENCE):
 * нумерация наблюдателя — не идентичность. Наблюдения разных исполнителей сходятся в один
 * узел по смысловым якорям; неоднозначность — честное UNKNOWN, а не насильный merge.
 */
class StructuralCorrespondenceTest {

    private val apples = "яблоки"
    private val pears = "груши"
    private val price = "Цена"

    private fun known(vararg cells: Pair<String, String>): Map<String, String> = buildMap {
        cells.forEach { (row, col) ->
            val key = anchoredCellKey(row, col)
            put(key, "x")
            put(key + META_ANCHOR_ROW_SUFFIX, row)
            put(key + META_ANCHOR_COL_SUFFIX, col)
        }
    }

    @Test fun `одна клетка при разной нумерации наблюдателей — один узел`() {
        // Наблюдатель A назвал место якорями; наблюдатель B считал колонки иначе —
        // но якоря те же, и это тот же узел.
        val graph = known(apples to price)

        val place = structuralCorrespondence(graph, apples, price)

        assertTrue(place is Correspondence.Same)
        assertEquals(anchoredCellKey(apples, price), place.key)
    }

    @Test fun `соседняя строка с тем же значением — другой узел`() {
        val graph = known(apples to price)

        val place = structuralCorrespondence(graph, pears, price)

        assertTrue("груши слились с яблоками", place is Correspondence.Fresh)
    }

    @Test fun `якорь-уточнение — то же место`() {
        val graph = known("Огірки свіжі" to price)

        val place = structuralCorrespondence(graph, "Огірки", price)

        assertTrue("уточнённый якорь родил второй узел", place is Correspondence.Same)
    }

    @Test fun `двусмысленный якорь — честное UNKNOWN, не насильный merge`() {
        val graph = known("Капуста білоголова свіжа" to price, "Капуста квашена" to price)

        val place = structuralCorrespondence(graph, "Капуста", price)

        assertTrue(place is Correspondence.Unknown)
        assertEquals(2, (place as Correspondence.Unknown).candidates.size)
    }

    @Test fun `якорная форма ответа разбирается и сходится в канонический узел`() {
        val parsed = parseFieldCandidates("CELL «$apples» × «$price» = 78,00")

        val key = anchoredCellKey(apples, price)
        assertEquals("78,00", parsed.single[key])
        assertEquals(apples, parsed.single[key + META_ANCHOR_ROW_SUFFIX])
    }

    @Test fun `форматы числа в одном узле складываются merge-ом как раньше`() {
        val key = anchoredCellKey(apples, price)

        val merged = mergeFacts(mapOf(key to "78,00"), mapOf(key to "78.00"))

        assertTrue(merged.getValue(key).isNotBlank())
        assertTrue(merged[key + META_ALT_SUFFIX].isNullOrBlank())
    }

    @Test fun `resolveStructural сводит свежие якорные значения к существующему узлу`() {
        val graph = known("Огірки свіжі" to price)
        val freshKey = anchoredCellKey("Огірки", price)
        val fresh = mapOf(
            freshKey to "9,560",
            freshKey + META_ANCHOR_ROW_SUFFIX to "Огірки",
            freshKey + META_ANCHOR_COL_SUFFIX to price,
        )

        val (values, anchors) = resolveStructural(graph, fresh)

        val canonical = anchoredCellKey("Огірки свіжі", price)
        assertEquals("9,560", values[canonical])
        assertTrue(anchors.containsKey(canonical + META_ANCHOR_ROW_SUFFIX))
        assertFalse("наблюдательский ключ просочился мимо канона", values.containsKey(freshKey))
    }

    @Test fun `якорный вопрос брифа слеп и адресует якорями`() {
        val key = anchoredCellKey("Цукор", "Ціна")
        val brief = spiralBrief(
            mapOf(
                key to "500",
                key + META_ANCHOR_ROW_SUFFIX to "Цукор",
                key + META_ANCHOR_COL_SUFFIX to "Ціна",
                key + META_EVIDENCE_SUFFIX to "",
            ),
        )!!

        assertTrue(brief.contains("«Цукор»") && brief.contains("«Ціна»"))
        assertFalse("бриф подсказал проверяемое значение", brief.contains("500"))
    }

    @Test fun `узел без якорей в бриф не выходит — спросить его нечем`() {
        val key = anchoredCellKey("х", "у")
        val brief = spiralBrief(
            mapOf(key to "1", key + META_EVIDENCE_SUFFIX to "", META_SEMANTIC_SUMMARY to "т"),
        )!!

        assertFalse(brief.contains("CELL «"))
    }
}
