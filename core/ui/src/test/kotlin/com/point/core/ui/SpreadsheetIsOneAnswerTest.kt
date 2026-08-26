package com.point.core.ui

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * «Это таблица?» — один ответ на весь модуль (#1233).
 *
 * Раньше вопрос решали два разных списка форматов: узкий кормил подпись героя, широкий — знак и
 * чип. На `прайс.doc` с mime `text/csv` один экран говорил про один объект две разные вещи:
 * зелёный знак «Таблица» и заголовок «Документ». Проверки узкого детектора переехали сюда, к
 * `kindMarkOf`, — теперь они охраняют тот самый детектор, который виден человеку.
 */
class SpreadsheetIsOneAnswerTest {

    private val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val pptx = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    @Test
    fun `точный mime таблицы даёт знак таблицы`() {
        assertEquals(KindMark.SPREADSHEET, kindMarkOf(ObjectKind.OFFICE, xlsx, "таблица.xlsx"))
    }

    @Test
    fun `таблица без имени файла всё равно узнаётся по mime`() {
        assertEquals(KindMark.SPREADSHEET, kindMarkOf(ObjectKind.OFFICE, xlsx))
    }

    @Test
    fun `xlsx, приехавший безымянным типом, узнаётся по расширению`() {
        assertEquals(
            KindMark.SPREADSHEET,
            kindMarkOf(ObjectKind.OFFICE, "application/octet-stream", "таблица.xlsx"),
        )
        assertEquals(
            KindMark.SPREADSHEET,
            kindMarkOf(ObjectKind.OFFICE, "application/zip", "выгрузка.XLSX"),
        )
    }

    @Test
    fun `старый xls — тоже таблица`() {
        assertEquals(KindMark.SPREADSHEET, kindMarkOf(ObjectKind.OFFICE, "application/vnd.ms-excel", "смета.xls"))
        assertEquals(KindMark.SPREADSHEET, kindMarkOf(ObjectKind.OFFICE, "application/octet-stream", "смета.xls"))
    }

    @Test
    fun `параметры и регистр в mime не мешают`() {
        assertEquals(KindMark.SPREADSHEET, kindMarkOf(ObjectKind.OFFICE, "$xlsx; charset=utf-8"))
        assertEquals(KindMark.SPREADSHEET, kindMarkOf(ObjectKind.OFFICE, xlsx.uppercase()))
    }

    @Test
    fun `docx и pptx остаются документом`() {
        assertEquals(KindMark.DOCUMENT, kindMarkOf(ObjectKind.OFFICE, docx, "договор.docx"))
        assertEquals(KindMark.DOCUMENT, kindMarkOf(ObjectKind.OFFICE, pptx, "презентация.pptx"))
    }

    @Test
    fun `документ, назвавший себя точным mime, не переодевается именем файла`() {
        assertEquals(KindMark.DOCUMENT, kindMarkOf(ObjectKind.OFFICE, docx, "почему-то.xlsx"))
    }

    @Test
    fun `вид объекта решает — картинка с именем таблицы остаётся картинкой`() {
        assertEquals(KindMark.IMAGE, kindMarkOf(ObjectKind.IMAGE, "image/png", "таблица.xlsx"))
        assertNotEquals(KindMark.SPREADSHEET, kindMarkOf(ObjectKind.TEXT, "text/csv", "выгрузка.csv"))
    }

    @Test
    fun `офисный документ без единой подсказки получает общий знак документа`() {
        assertEquals(KindMark.DOCUMENT, kindMarkOf(ObjectKind.OFFICE, "application/octet-stream"))
    }

    @Test
    fun `у готового объекта имя берётся из метаданных`() {
        assertEquals(KindMark.SPREADSHEET, kindMarkOf(office("application/octet-stream", "таблица.xlsx")))
    }

    /**
     * Ловушка, на которой разъезжались два детектора: расширение офисного документа при
     * табличном mime. Знак и подпись обязаны сказать одно и то же — кто бы из них ни ошибся.
     */
    @Test
    fun `знак и подпись героя дают один ответ на спорном объекте`() {
        val tricky = office("text/csv", "прайс.doc")

        assertEquals(kindMarkLabel(kindMarkOf(tricky)), objectVerdict(tricky).headline)
    }

    @Test
    fun `знак и подпись героя не расходятся ни на одном известном формате`() {
        val names = listOf(
            "таблица.xlsx", "смета.xls", "макрос.xlsm", "двоичная.xlsb",
            "открытая.ods", "выгрузка.csv", "договор.docx", "прайс.doc",
            "презентация.pptx", "слайды.ppt", "текст.odt", null,
        )
        val mimes = listOf(
            xlsx, docx, pptx, "text/csv", "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.ms-excel", "application/msword", "application/octet-stream",
        )

        mimes.forEach { mime ->
            names.forEach { name ->
                val obj = office(mime, name)
                assertEquals(
                    "знак и подпись разошлись на «$name» с типом «$mime»",
                    kindMarkLabel(kindMarkOf(obj)),
                    objectVerdict(obj).headline,
                )
            }
        }
    }

    private fun office(mime: String, name: String?) = PointObject(
        id = "o1",
        mime = mime,
        uri = ScratchRef("/scratch/o1"),
        state = ObjectState(ObjectKind.OFFICE),
        metadata = name?.let { mapOf("name" to it) } ?: emptyMap(),
    )
}
