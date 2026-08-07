package com.point.core.ui

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetMarkTest {

    private val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val pptx = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    @Test
    fun `точный mime таблицы даёт знак таблицы`() {
        assertEquals(ObjectMark.SPREADSHEET, objectMark(ObjectKind.OFFICE, xlsx, "таблица.xlsx"))
    }

    @Test
    fun `таблица без имени файла всё равно узнаётся по mime`() {
        assertEquals(ObjectMark.SPREADSHEET, objectMark(ObjectKind.OFFICE, xlsx))
    }

    @Test
    fun `xlsx, приехавший безымянным типом, узнаётся по расширению`() {

        assertEquals(
            ObjectMark.SPREADSHEET,
            objectMark(ObjectKind.OFFICE, "application/octet-stream", "таблица.xlsx"),
        )
        assertEquals(
            ObjectMark.SPREADSHEET,
            objectMark(ObjectKind.OFFICE, "application/zip", "выгрузка.XLSX"),
        )
    }

    @Test
    fun `старый xls — тоже таблица`() {
        assertEquals(ObjectMark.SPREADSHEET, objectMark(ObjectKind.OFFICE, "application/vnd.ms-excel", "смета.xls"))
        assertEquals(ObjectMark.SPREADSHEET, objectMark(ObjectKind.OFFICE, "application/octet-stream", "смета.xls"))
    }

    @Test
    fun `параметры и регистр в mime не мешают`() {
        assertEquals(ObjectMark.SPREADSHEET, objectMark(ObjectKind.OFFICE, "$xlsx; charset=utf-8"))
        assertEquals(ObjectMark.SPREADSHEET, objectMark(ObjectKind.OFFICE, xlsx.uppercase()))
    }

    @Test
    fun `docx и pptx остаются общим документом`() {
        assertEquals(ObjectMark.GENERIC, objectMark(ObjectKind.OFFICE, docx, "договор.docx"))
        assertEquals(ObjectMark.GENERIC, objectMark(ObjectKind.OFFICE, pptx, "презентация.pptx"))
    }

    @Test
    fun `документ, назвавший себя точным mime, не переодевается именем файла`() {
        assertEquals(ObjectMark.GENERIC, objectMark(ObjectKind.OFFICE, docx, "почему-то.xlsx"))
    }

    @Test
    fun `вид объекта решает — картинка с именем таблицы остаётся картинкой`() {
        assertEquals(ObjectMark.GENERIC, objectMark(ObjectKind.IMAGE, "image/png", "таблица.xlsx"))
        assertEquals(ObjectMark.GENERIC, objectMark(ObjectKind.TEXT, "text/csv", "выгрузка.csv"))
    }

    @Test
    fun `офисный документ без единой подсказки получает общий знак`() {
        assertEquals(ObjectMark.GENERIC, objectMark(ObjectKind.OFFICE, "application/octet-stream"))
    }

    @Test
    fun `у готового объекта имя берётся из метаданных`() {
        val obj = PointObject(
            id = "o1",
            mime = "application/octet-stream",
            uri = ScratchRef("/scratch/o1"),
            state = ObjectState(ObjectKind.OFFICE),
            metadata = mapOf("name" to "таблица.xlsx"),
        )
        assertEquals(ObjectMark.SPREADSHEET, objectMark(obj))
    }

    @Test
    fun `фаза не начинается раньше своего времени и не переливается за край`() {
        assertEquals(0f, markSegment(0.10f, 0.2f, 0.6f), 1e-4f)
        assertEquals(0.5f, markSegment(0.40f, 0.2f, 0.6f), 1e-4f)
        assertEquals(1f, markSegment(0.90f, 0.2f, 0.6f), 1e-4f)
    }

    @Test
    fun `знак Excel загорается последним — позже, чем наполняются строки`() {

        assertTrue("лист рождается первым", MARK_SHEET.from < MARK_GRID.from)
        assertTrue("сетка ложится раньше значений", MARK_GRID.from < MARK_ROWS.from)
        assertTrue("«X» загорается последним", MARK_ROWS.from < MARK_IGNITE.from)
        assertEquals(0f, markSegment(MARK_ROWS.from + 0.01f, MARK_IGNITE), 1e-4f)
    }

    @Test
    fun `рождение занимает всё время без мёртвых пауз и к концу дорисовано`() {
        val phases = listOf(MARK_SHEET, MARK_GRID, MARK_ROWS, MARK_IGNITE)
        assertEquals("знак начинает рождаться сразу", 0f, phases.first().from, 1e-4f)
        assertEquals("к концу знак дорисован", 1f, phases.last().to, 1e-4f)
        phases.zipWithNext { current, next ->
            assertTrue("фазы перетекают, а не ждут друг друга", next.from < current.to)
        }
        phases.forEach { assertEquals("${it} должна быть пройдена", 1f, markSegment(1f, it), 1e-4f) }
    }
}
