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

/**
 * Якорь «На новый период» (#224): признак зажигается ровно тогда, когда в листе прочитан
 * календарь дат, — и никогда «на всякий случай».
 */
class PeriodEnricherTest {

    private val table = PointObject(
        "id",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ScratchRef("/tmp/t.xlsx"),
        ObjectState(ObjectKind.OFFICE),
    )

    private fun enricherOver(rows: List<List<String>>) = PeriodEnricher(
        object : SpreadsheetReader {
            override suspend fun readRows(obj: PointObject): List<List<String>> = rows
        },
    )

    private fun failingReader() = PeriodEnricher(
        object : SpreadsheetReader {
            override suspend fun readRows(obj: PointObject): List<List<String>> = error("не таблица")
        },
    )

    @Test
    fun `график за две недели получает признак периода`() = runTest {
        val rows = listOf(listOf("Захід", "Дата")) +
            (16..29).map { day -> listOf("Захід $day", "%02d.07.2026".format(day)) }

        val delta = enricherOver(rows).enrich(table)

        assertEquals(setOf(Feature.HAS_PERIOD), delta.features)
        assertTrue("признак — единственное, что мы утверждаем", delta.metadata.isEmpty())
        assertTrue(delta.objects.isEmpty())
    }

    @Test
    fun `реестр договоров признака не получает`() = runTest {
        // Кадр 06 корпуса: даты есть, подряд идущих дней нет — периода нет и пузырька нет.
        val rows = listOf(
            listOf("Рік", "Номер", "Дата", "Постачальник", "Ціна", "діє з"),
            listOf("2018", "—", "18.10.18", "—", "78.00", "24.10.18"),
            listOf("2018", "—", "11.10.18", "—", "73,00", "01.10.18"),
            listOf("2018", "—", "18.12.18", "—", "78.00", "17.12.18"),
            listOf("2018", "—", "18.10.18", "—", "78.00", "22.10.18"),
            listOf("2018", "—", "05.10.18", "—", "78.00", "18.10.18"),
        )

        assertTrue(enricherOver(rows).enrich(table).features.isEmpty())
    }

    @Test
    fun `нечитаемый файл — не сбой, а просто отсутствие признака`() = runTest {
        assertTrue(failingReader().enrich(table).features.isEmpty())
    }

    @Test
    fun `смотрит только на документы`() {
        val enricher = enricherOver(emptyList())
        assertTrue(enricher.appliesTo(ObjectState(ObjectKind.OFFICE)))
        assertFalse(enricher.appliesTo(ObjectState(ObjectKind.IMAGE)))
        assertFalse(enricher.appliesTo(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `ярлык не обещает таблицу — виден он над любым документом`() {
        // `appliesTo` судит по виду объекта, а вид у .docx и .xlsx один. Значит строка «Point
        // думает» встанет и над письмом в Word — а до этого среза над документами не работал ни
        // один энричер, и карточки там не было вовсе. Обещать таблицу там, где её нет, — тихое
        // враньё на пустом месте.
        val label = checkNotNull(enricherOver(emptyList()).meta.label)
        assertFalse("над .docx это будет неправдой", label.contains("таблиц", ignoreCase = true))
    }
}
