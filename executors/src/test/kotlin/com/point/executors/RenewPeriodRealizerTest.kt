package com.point.executors

import com.point.core.flow.SpreadsheetReader
import com.point.core.flow.SpreadsheetWriter
import com.point.core.model.ActionResult
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
import java.io.File

/**
 * «На новый период» над таблицей (#224): читаем лист, сдвигаем календарь, очищаем то, что
 * заполняется заново, и **называем сделанное вслух**.
 *
 * Таблица — график мероприятий из заявки #224 (шапка, даты и время дословны; фамилии
 * ответственных заменены — данные владельца в репозитории не живут).
 */
class RenewPeriodRealizerTest {

    private val sheet: List<List<String>> = buildList {
        add(listOf("Захід", "Дата", "Час", "Відповідальний", "Підпис"))
        (16..29).forEachIndexed { i, day ->
            add(
                listOf(
                    "Захід $day",
                    "%02d.07.2026".format(day),
                    if (i % 2 == 0) "8-00" else "17-30",
                    if (i % 2 == 0) "Відповідальний 1" else "Відповідальний 2",
                    "підпис $day",
                ),
            )
        }
    }

    private fun readerOf(rows: List<List<String>>) = object : SpreadsheetReader {
        override suspend fun readRows(obj: PointObject): List<List<String>> = rows
    }

    private var written: List<List<String>>? = null
    private val writer = object : SpreadsheetWriter {
        override suspend fun write(
            rows: List<List<String>>,
            candidates: Map<Pair<Int, Int>, List<String>>,
        ): ScratchRef {
            written = rows
            return ScratchRef(
                File.createTempFile("point-renew", ".xlsx").apply { deleteOnExit() }.absolutePath,
            )
        }
    }

    private val table = PointObject(
        "id",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ScratchRef("/tmp/t.xlsx"),
        ObjectState(ObjectKind.OFFICE, setOf(Feature.HAS_PERIOD)),
    )

    @Test
    fun `продлевает график и рассказывает, что сделал`() = runTest {
        val result = RenewPeriodRealizer(readerOf(sheet), writer).perform(table, null)
        val success = result as ActionResult.Success
        val rows = checkNotNull(written)

        assertEquals("шапка не тронута", sheet[0], rows[0])
        assertEquals(listOf("", "30.07.2026", "8-00", "Відповідальний 1", ""), rows[1])
        assertEquals("12.08.2026", rows[14][1])

        assertEquals("renew-period", success.result.metadata["op"])
        assertEquals("бланк 30.07.2026-12.08.2026.xlsx", success.result.metadata["name"])
        assertEquals("14", success.result.metadata["shifted"])
        val summary = checkNotNull(success.result.metadata["semantic.summary"])
        assertTrue("назван новый период", summary.startsWith("Бланк на 30.07.2026 – 12.08.2026"))
        assertTrue("назван прошлый период", "(был 16.07 – 29.07)" in summary)
        assertTrue("очищенное названо поимённо", "очищено, у каждой даты своё: Захід, Підпис" in summary)
        assertTrue("оставленное тоже названо", "оставлено: Час, Відповідальний" in summary)
    }

    @Test
    fun `таблица без календаря дат — честный отказ, а не пустой бланк`() = runTest {
        // Признак мог загореться на другой копии таблицы; в этом случае действие обязано
        // отказать, а не собрать документ неизвестно за какой период.
        val ledger = listOf(
            listOf("Арт.№", "Найменування", "Рота зв'язку"),
            listOf("11004", "Буряк столовий свіжий", "6,003"),
            listOf("11008", "Ікра кабачкова", "2,04 1,994"),
        )
        val result = RenewPeriodRealizer(readerOf(ledger), writer).perform(table, null)
        val failure = result as ActionResult.Failure
        assertTrue("причина названа человеку", "продлевать нечего" in failure.reason)
        assertFalse("повтор не поможет — тот же лист", failure.recoverable)
        assertEquals("ничего не записано", null, written)
    }

    @Test
    fun `пузырёк живёт только там, где период прочитан`() {
        val capability = RenewPeriodCapability()
        assertTrue(capability.accepts(ObjectState(ObjectKind.OFFICE, setOf(Feature.HAS_PERIOD))))
        assertFalse("обычная таблица — не бланк за период", capability.accepts(ObjectState(ObjectKind.OFFICE)))
        assertFalse(
            "картинка с датами тоже не бланк",
            capability.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_PERIOD))),
        )
        assertFalse("подсказки «почти доступно» нет — это был бы шум на каждом документе",
            capability.missing(ObjectState(ObjectKind.OFFICE)) != null)
    }
}
