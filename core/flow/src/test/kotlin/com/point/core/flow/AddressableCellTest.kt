package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ячейка — первый адресуемый структурный узел Graph (#1176, эксперимент CELL):
 * обычный факт с идентичностью в ключе. Спор, улики, актёры, согласие и дельта
 * достаются существующей механикой — без отдельного конвейера.
 */
class AddressableCellTest {

    private val cell = cellKey(3, 5)

    @Test fun `ответ по адресу разбирается в ячейку, соседние не сливаются`() {
        val parsed = parseFieldCandidates("CELL r3 c5 = 78,00\nCELL r3 c6 = 93")

        assertEquals("78,00", parsed.single[cellKey(3, 5)])
        assertEquals("93", parsed.single[cellKey(3, 6)])
    }

    @Test fun `форматы одного числа — одно знание ячейки`() {
        assertTrue(sameFact(cell, "78,00", "78.00"))
        assertFalse(sameFact(cell, "78,00", "79,00"))
    }

    @Test fun `расхождение чтений ячейки — спор, не молчаливая замена`() {
        val carrot = "Морква свіжа"
        val olives = "Маслини без кісточок"

        val merged = mergeFacts(mapOf(cell to carrot), mapOf(cell to olives))

        assertEquals(carrot, merged[cell])
        assertTrue(merged.getValue(cell + META_ALT_SUFFIX).contains(olives))
    }

    @Test fun `согласие двух актёров подтверждает ячейку той же уликой`() {
        val evidence = agreementEvidence(
            mapOf(cell to "78,00", cell + META_ACTOR_SUFFIX to "excel,gemini"),
            listOf(cell),
        )

        val marks = evidence.getValue(cell + META_EVIDENCE_SUFFIX)
        assertTrue(marks.contains(AGREE_MARK + "excel") && marks.contains(AGREE_MARK + "gemini"))
    }

    @Test fun `спорная ячейка зовётся в бриф с адресом и форматом ответа`() {
        val brief = spiralBrief(
            mapOf(
                cell to "Морква свіжа",
                cell + META_ALT_SUFFIX to "Маслини без кісточок",
            ),
        )!!

        assertTrue(brief.contains("строка 3, колонка 5"))
        assertTrue(brief.contains("CELL r3 c5"))
        assertTrue(brief.contains("Морква") && brief.contains("Маслини"))
    }

    @Test fun `сомнительная ячейка просит проверки, бесспорная в бриф не идёт`() {
        val doubted = spiralBrief(
            mapOf(cell to "9,560", cell + META_EVIDENCE_SUFFIX to ""),
        )!!
        assertTrue(doubted.contains("CELL r3 c5"))
        // Слепая перепроверка: подсказанное значение модель возвращает эхом, и два имени
        // актёров дают одно наблюдение (живой прогон 20.08, RFC §8).
        assertFalse("бриф подсказал проверяемое значение", doubted.contains("9,560"))

        val settled = spiralBrief(
            mapOf(
                cell to "9,560",
                cell + META_EVIDENCE_SUFFIX to AGREE_MARK + "a," + AGREE_MARK + "b",
                META_SEMANTIC_SUMMARY to "Таблица",
            ),
        )!!
        assertFalse("бесспорная ячейка шумит в брифе", settled.contains("CELL r3 c5"))
    }

    @Test fun `большинство накопленных прочтений побеждает, проигравшее остаётся историей`() {
        val excel = "Маслини без кісточок"
        val truth = "Морква свіжа"

        // Виток 2: зрячая модель спорит с excel-чтением.
        val round2 = mergeFacts(mapOf(cell to excel), mapOf(cell to truth))
        assertEquals(excel, round2[cell])

        // Виток 3: второй зрячий подтверждает — двое против одного.
        val round3 = mergeFacts(round2, mapOf(cell to truth))
        assertEquals(truth, round3[cell])
        assertTrue("проигравшее чтение исчезло молча", round3.getValue(cell + META_ALT_SUFFIX).contains(excel))
    }

    @Test fun `дельта называет ячейку по-человечески`() {
        val delta = spiralDelta(emptyMap(), mapOf(cell to "78,00"))!!

        assertTrue(delta.contains("Ячейка 3×5"))
    }

    @Test fun `чужой ключ ячейкой не притворяется`() {
        assertNull(cellAddress("entity.phone"))
        assertNull(cellAddress("cell.весёлый.мусор"))
    }
}
