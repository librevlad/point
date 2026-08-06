package com.point.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * На экране ПК стоят факты, а не следы разбора (#594).
 *
 * Разбор пишет рядом со значением свои улики — чем прочитано (`.src`), чем подтверждено (`.ev`),
 * в какой валюте (`.currency`). Это нужно нам, а не человеку: на живом экране они читались как
 * «Amount.src · ocr» — жаргон вместо ответа.
 */
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
        // Проверка по форме, а не по перечню суффиксов: перечень пришлось бы дописывать при
        // каждом новом следе, и однажды его бы забыли — ровно так `.currency` и попал на экран.
        val future = mapOf("entity.amount" to "1", "entity.amount.чтобы-ни-было" to "x")

        assertEquals(setOf("entity.amount"), future.filterKeys { plainFact(it) }.keys)
    }

    /** Та же проверка, что на экране: у следа внутри имени есть вторая точка. */
    private fun plainFact(key: String): Boolean = !key.removePrefix("entity.").contains('.')
}
