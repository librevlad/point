package com.point.desktop

import com.point.core.flow.FindRealizer
import com.point.core.flow.META_OCR_TEXT_CUT
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_TEXT
import com.point.core.flow.READ_TEXT_TRAVEL_LIMIT
import com.point.core.flow.READ_TEXT_TRAVEL_READ
import com.point.core.flow.foundOnPageLabel
import com.point.core.flow.knowledgePackedForTravel
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Прочитанное на телефоне остаётся прочитанным на компьютере (#811).
 *
 * Живой прогон 11.08.2026: снимок, прочитанный на телефоне, приезжал на компьютер
 * «непрочитанным» — там первым действием предлагали «Распознать текст». Знание ехало
 * ссылкой `ocr.text.ref` на scratch-файл телефона, а на той стороне такой ссылки нет и быть
 * не может.
 */
class KnowledgeTravelsWithObjectTest {

    @get:Rule val temp = TemporaryFolder()

    private fun inbox() = Inbox(temp.newFolder("inbox"))

    private val read = "X Detach tab · Fit to window size · 11/11/2025"

    @Test
    fun `приехавший текст становится знанием, а не строкой метаданных`() {
        val item = inbox().receive(
            name = "снимок.png",
            mime = "image/png",
            meta = mapOf(META_READ_TEXT to read),
            source = "PNG".byteInputStream(),
        )

        assertTrue("компьютер знает, что текст есть", item.obj.state.has(Feature.HAS_TEXT))

        val kept = item.obj.metadata[META_OCR_TEXT_REF]
        assertTrue("текст лежит файлом рядом", kept != null && File(kept).isFile)
        assertEquals(read, File(kept!!).readText())
    }

    @Test
    fun `значение в метаданных не остаётся — знание живёт своим местом`() {
        val item = inbox().receive(
            name = "снимок.png",
            mime = "image/png",
            meta = mapOf(META_READ_TEXT to read),
            source = "PNG".byteInputStream(),
        )

        assertFalse(item.obj.metadata.containsKey(META_READ_TEXT))
    }

    @Test
    fun `объект без прочитанного текста приезжает как раньше`() {
        val item = inbox().receive(
            name = "снимок.png",
            mime = "image/png",
            meta = mapOf("name" to "снимок.png"),
            source = "PNG".byteInputStream(),
        )

        assertFalse("текста не было — и признака нет", item.obj.state.has(Feature.HAS_TEXT))
        assertFalse(item.obj.metadata.containsKey(META_OCR_TEXT_REF))
    }

    /** Прочитанный договор длиннее дороги: слово, которое ищут, стоит уже за её пределом. */
    private val longContract = "умови договору ".repeat(10_000) + "Іванов"

    /**
     * Ловит находку ревью #1014: скан, прочитанный на телефоне, доезжает сюда началом текста,
     * и «Найти в документе» на компьютере отвечало «Ничего не нашлось» про слово, которого
     * здесь просто нет. Начало на месте — и сказано, что это только начало.
     */
    @Test
    fun `обрезок с телефона ищется здесь с оговоркой, а не как весь документ`() = runBlocking {
        val fromPhone = knowledgePackedForTravel(
            mapOf(META_OCR_TEXT_REF to "/телефон/прочитане.txt"),
            longContract.take(READ_TEXT_TRAVEL_READ),
        )
        val item = inbox().receive(
            name = "договір.pdf",
            mime = "application/pdf",
            meta = fromPhone,
            source = "%PDF-1.4".byteInputStream(),
        )

        val said = (FindRealizer().perform(item.obj, "Іванов") as ActionResult.Done).message

        assertNotEquals("про слово, которое сюда не доехало, сказано «ничего не нашлось»", foundOnPageLabel(0), said)
    }

    /** Обратный путь — с компьютера на телефон — тем же правилом: начало названо началом (#1014). */
    @Test
    fun `длинный текст едет на телефон началом и назван обрезком`() {
        val read = temp.newFile("прочитане.txt").apply { writeText(longContract) }

        val meta = packedForTravel(mapOf(META_OCR_TEXT_REF to read.absolutePath))

        assertEquals(READ_TEXT_TRAVEL_LIMIT, meta[META_READ_TEXT]?.length)
        assertEquals(
            "обрезок уехал на телефон без пометки — там его примут за весь документ",
            READ_TEXT_TRAVEL_LIMIT.toString(),
            meta[META_OCR_TEXT_CUT],
        )
    }
}
