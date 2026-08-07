package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class FactsShownTest {

    private val metadata = mapOf(
        "entity.amount" to "128500",
        "entity.amount.currency" to "руб.",
        "entity.amount.src" to "ocr",
        "entity.amount.ev" to "semantic",
        "entity.phone" to "+7 916 123-45-67",
        "entity.track.ev" to "semantic,geometric",
        "name" to "Счёт 4512",
    )

    @Test fun `человеку показываются только сами значения`() {
        val shown = metadata.filterKeys { it.startsWith("entity.") && plainFact(it) }

        assertEquals(setOf("entity.amount", "entity.phone"), shown.keys)
    }

    @Test fun `новый след разбора не придётся вносить в список`() {

        val future = mapOf("entity.amount" to "1", "entity.amount.чтобы-ни-было" to "x")

        assertEquals(setOf("entity.amount"), future.filterKeys { plainFact(it) }.keys)
    }

    private fun plainFact(key: String): Boolean = !key.removePrefix("entity.").contains('.')
}
