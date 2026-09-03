package com.point.data

import com.point.core.flow.SpreadsheetReader
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodInvestigationTest {

    private val table = PointObject(
        "id",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ScratchRef("/tmp/t.xlsx"),
        ObjectState(ObjectKind.OFFICE),
    )

    private fun enricherOver(rows: List<List<String>>) = PeriodInvestigationRealizer(
        object : SpreadsheetReader {
            override suspend fun readRows(obj: PointObject): List<List<String>> = rows
        },
    )

    private fun failingReader() = PeriodInvestigationRealizer(
        object : SpreadsheetReader {
            override suspend fun readRows(obj: PointObject): List<List<String>> = error("не таблица")
        },
    )

    private fun enricherOverSheets(vararg sheets: com.point.core.flow.NamedSheet) = PeriodInvestigationRealizer(
        object : SpreadsheetReader {
            override suspend fun readRows(obj: PointObject): List<List<String>> = sheets.first().rows
            override suspend fun readSheets(obj: PointObject): List<com.point.core.flow.NamedSheet> = sheets.toList()
        },
    )

    private val schedule: List<List<String>> = listOf(listOf("Захід", "Дата")) +
        (16..29).map { day -> listOf("Захід $day", "%02d.07.2026".format(day)) }

    /**
     * Живая охота 03.09.2026 (#1417), книга владельца «Їдальня»: первый лист — шаблон без дат,
     * 184 даты подряд — на втором. Исследование смотрело один лист и отвечало «не найдено» за книгу.
     */
    @Test
    fun `период на втором листе находится и лист называется`() = runTest {
        val template = com.point.core.flow.NamedSheet("template", listOf(listOf("Шапка"), listOf("Код номенклатури")))
        val daily = com.point.core.flow.NamedSheet("daily-2025", schedule)

        val delta = enricherOverSheets(template, daily).look(table)

        assertEquals(setOf(Feature.HAS_PERIOD), delta.features)
        assertEquals(daily.name, delta.metadata[com.point.core.flow.META_PERIOD_SHEET])
    }

    @Test
    fun `период на двух листах — назван первый из них`() = runTest {
        val july = com.point.core.flow.NamedSheet("july", schedule)
        val august = com.point.core.flow.NamedSheet("august", schedule)

        val delta = enricherOverSheets(july, august).look(table)

        assertEquals(july.name, delta.metadata[com.point.core.flow.META_PERIOD_SHEET])
    }

    @Test
    fun `график за две недели получает признак периода`() = runTest {
        val rows = listOf(listOf("Захід", "Дата")) +
            (16..29).map { day -> listOf("Захід $day", "%02d.07.2026".format(day)) }

        val delta = enricherOver(rows).look(table)

        assertEquals(setOf(Feature.HAS_PERIOD), delta.features)
        assertTrue("признак — единственное, что мы утверждаем", delta.metadata.isEmpty())
        assertTrue(delta.objects.isEmpty())
    }

    @Test
    fun `реестр договоров признака не получает`() = runTest {

        val rows = listOf(
            listOf("Рік", "Номер", "Дата", "Постачальник", "Ціна", "діє з"),
            listOf("2018", "—", "18.10.18", "—", "78.00", "24.10.18"),
            listOf("2018", "—", "11.10.18", "—", "73,00", "01.10.18"),
            listOf("2018", "—", "18.12.18", "—", "78.00", "17.12.18"),
            listOf("2018", "—", "18.10.18", "—", "78.00", "22.10.18"),
            listOf("2018", "—", "05.10.18", "—", "78.00", "18.10.18"),
        )

        assertTrue(enricherOver(rows).look(table).features.isEmpty())
    }

    @Test
    fun `нечитаемый файл — сбой операции, а не знание об отсутствии признака`() = runTest {
        val result = failingReader().perform(table, null)

        assertTrue("ADR-0001 §9- провал не превращается в «не найдено»-" + result,
            result is com.point.core.model.ActionResult.Failure)
    }

    @Test
    fun `смотрит только на документы`() {
        val enricher = enricherOver(emptyList())
        assertTrue(PeriodInvestigation().accepts(ObjectState(ObjectKind.OFFICE)))
        assertFalse(PeriodInvestigation().accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(PeriodInvestigation().accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `ярлык не обещает таблицу — виден он над любым документом`() {

        val label = PeriodInvestigation().label(ObjectState(ObjectKind.OFFICE))
        assertFalse("над .docx это будет неправдой", label.contains("таблиц", ignoreCase = true))
    }
}
