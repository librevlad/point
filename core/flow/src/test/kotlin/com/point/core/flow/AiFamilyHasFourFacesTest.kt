package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Соседи по списку носят разные знаки (#1384).
 *
 * «Понять», «Исправить ошибки», «Исправить сильнее» и «AI» стояли рядом с одним и тем же
 * значком и читались как дубли одного действия: разбор экрана внешним взглядом назвал это
 * прямо. Действия разные, и список обязан говорить об этом раньше, чем человек дочитает
 * названия.
 *
 * Семья при этом остаётся семьёй: тон у всех четырёх общий, врозь их разводит знак, а не цвет
 * (см. `bubbleColor` — четыре одинаковых значения рядом с четырьмя разными знаками).
 */
class AiFamilyHasFourFacesTest {

    private val ready = AiReadiness { true }

    private val family = listOf(
        UnderstandCapability(ready),
        FixErrorsCapability(ready),
        FixErrorsStrongerCapability(ready),
        AiCapability(ready),
    )

    @Test
    fun `у четырёх соседей четыре разных знака`() {
        val faces = family.map { it.icon }

        assertEquals("значки повторяются: " + faces.joinToString(), faces.size, faces.toSet().size)
    }

    @Test
    fun `ни один из них не остался без знака`() {
        family.forEach { assertEquals("действие без знака: " + it.id, false, it.icon.isBlank()) }
    }
}
