package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.DocBlock
import com.point.core.flow.DocStyle
import com.point.core.flow.DocxWriter
import com.point.core.flow.FrameTransform
import com.point.core.flow.LlmClient
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.ReadingMode
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** «В Word+» (#128): the AI twin of «В Word» — the LLM lays the raw text out as a
 *  structured document (title/headings/bullets) via a STRICT line contract. */
class WordPlusTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `parses the strict block contract and ignores garbage`() {
        val answer = """
            T=Отчёт о поездке
            P=Общие впечатления положительные.
            H=Расходы
            B=Отель — 3200
            B=Такси — 540
            мусор без префикса
            X=неизвестный ключ
        """.trimIndent()
        val blocks = parseDocBlocks(answer)
        assertEquals(
            listOf(
                DocBlock("Отчёт о поездке", DocStyle.TITLE),
                DocBlock("Общие впечатления положительные.", DocStyle.NORMAL),
                DocBlock("Расходы", DocStyle.HEADING),
                DocBlock("Отель — 3200", DocStyle.BULLET),
                DocBlock("Такси — 540", DocStyle.BULLET),
            ),
            blocks,
        )
    }

    @Test
    fun `accepts mirror the local twin and the meta is a paid network action`() {
        val cap = WordPlusCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.PDF)))
        assertTrue("картинку теперь тоже (OCR → Word+)", cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(cap.meta.network)
        assertEquals("В Word+", cap.label(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `runs the LLM answer through the styled writer`() = runTest {
        val src = File(tmp.root, "raw.txt").apply { writeText("сырой текст отчёта") }
        val ans = File(tmp.root, "ans.txt").apply { writeText("T=Отчёт\nB=пункт") }
        val out = File(tmp.root, "doc.docx").apply { writeText("zip") }
        var styled: List<DocBlock>? = null
        val writer = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef = ScratchRef(out.absolutePath)
            override suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef {
                styled = blocks
                return ScratchRef(out.absolutePath)
            }
        }
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                assertTrue(prompt.contains("сырой текст отчёта"))
                return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(ans.absolutePath))
            }
        }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))

        val ocr = object : TextRecognizer { override suspend fun recognize(obj: PointObject) = "" }
        val result = WordPlusRealizer(llm, object : PdfTextExtractor { override suspend fun extractText(obj: PointObject) = "" }, writer, ocr).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        assertEquals(DocStyle.TITLE, styled!![0].style)
        assertEquals("Отчёт", styled!![0].text)
    }

    // -- #288: стадии доходят до экрана --

    @Test
    fun `на PDF слышно все три шага — чтение, модель, сборка документа`() = runTest {
        val out = File(tmp.root, "doc.docx").apply { writeText("zip") }
        val ans = File(tmp.root, "ans.txt").apply { writeText("T=Отчёт\nB=пункт") }
        val writer = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef = ScratchRef(out.absolutePath)
            override suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef = ScratchRef(out.absolutePath)
        }
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) =
                ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(ans.absolutePath))
        }
        val pdf = PointObject("id", "application/pdf", ScratchRef("/tmp/x.pdf"), ObjectState(ObjectKind.PDF))
        val realizer = WordPlusRealizer(
            llm,
            object : PdfTextExtractor { override suspend fun extractText(obj: PointObject) = "сырой текст" },
            writer,
            object : TextRecognizer { override suspend fun recognize(obj: PointObject) = "" },
        )

        val heard = stagesHeard { realizer.perform(pdf, null) }

        assertEquals(listOf("Читаю текст PDF", "Модель размечает документ", "Собираю документ"), heard)
    }

    @Test
    fun `у текста шага чтения нет — читать нечего, и стадия о нём не выдумывается`() = runTest {
        val src = File(tmp.root, "raw.txt").apply { writeText("сырой текст отчёта") }
        val ans = File(tmp.root, "ans2.txt").apply { writeText("T=Отчёт") }
        val out = File(tmp.root, "doc2.docx").apply { writeText("zip") }
        val writer = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef = ScratchRef(out.absolutePath)
            override suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef = ScratchRef(out.absolutePath)
        }
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) =
                ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(ans.absolutePath))
        }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT))
        val realizer = WordPlusRealizer(
            llm,
            object : PdfTextExtractor { override suspend fun extractText(obj: PointObject) = "" },
            writer,
            object : TextRecognizer { override suspend fun recognize(obj: PointObject) = "" },
        )

        val heard = stagesHeard { realizer.perform(obj, null) }

        assertEquals(listOf("Модель размечает документ", "Собираю документ"), heard)
    }

    // -- #267: экспорт помечает неуверенное --

    @Test
    fun `на рукописи цифры уходят в документ помеченными`() {
        val blocks = parseDocBlocks(
            "T=Конспект\nP=Итого 1450 грн",
            com.point.core.flow.ReadingMode.HANDWRITTEN,
        )

        assertFalse("заголовок без цифр — чистый", blocks[0].uncertain)
        assertTrue("цифра рукописи обязана быть видна при вычитке", blocks[1].uncertain)
    }

    @Test
    fun `на печати те же цифры идут чистыми — их читал движок`() {
        val blocks = parseDocBlocks("P=Итого 1450 грн", com.point.core.flow.ReadingMode.PRINTED)

        assertFalse(blocks.single().uncertain)
    }

    // -- #267, вторая половина: к спорному фрагменту едет адрес кроп-улики --

    /** Ведомость глазами телефона: артикул печатный и читается, остальное в строке — каша. */
    private val sheetLayer = AtomLayer(
        listOf(
            Atom("w0", "11004", Box(0f, 100f, 60f, 120f)),
            Atom("w1", "3}3/9I=I", Box(100f, 100f, 200f, 120f)),
            Atom("w2", "11006", Box(0f, 200f, 60f, 220f)),
        ),
        transform = FrameTransform(sample = 2, rotationDegrees = 90, uprightWidth = 300, uprightHeight = 400),
    )

    private fun docxSpy(out: File, seen: (List<DocBlock>) -> Unit) = object : DocxWriter {
        override suspend fun write(paragraphs: List<String>): ScratchRef = ScratchRef(out.absolutePath)
        override suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef {
            seen(blocks)
            return ScratchRef(out.absolutePath)
        }
    }

    private fun answering(answer: File) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String) =
            ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(answer.absolutePath))
    }

    private val noPdf = object : PdfTextExtractor { override suspend fun extractText(obj: PointObject) = "" }

    private fun photo(name: String) = PointObject(
        "id", "image/jpeg", ScratchRef(File(tmp.root, name).apply { writeText("пиксели") }.absolutePath),
        ObjectState(ObjectKind.IMAGE),
        metadata = mapOf(META_READING_MODE to ReadingMode.HANDWRITTEN.name),
    )

    @Test
    fun `спорная строка фото уезжает в документ с адресом улики в сыром кадре`() = runTest {
        val ans = File(tmp.root, "ans-ev.txt").apply { writeText("T=Ведомость\nP=11004 Гречка 50") }
        var styled: List<DocBlock>? = null
        val obj = photo("23.jpg")
        val reader = object : AtomRecognizer { override suspend fun read(obj: PointObject) = sheetLayer }

        val result = WordPlusRealizer(
            answering(ans), noPdf, docxSpy(File(tmp.root, "ev.docx")) { styled = it }, reader,
        ).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        assertNull("заголовок без цифр — сверять нечего", styled!![0].evidence)
        assertNotNull("а помеченная строка знает своё место на бумаге", styled!![1].evidence)
        assertEquals(obj.uri.value, styled!![1].evidence!!.imagePath)
        assertEquals("кроп надо будет довернуть — файл лежит боком", 90, styled!![1].evidence!!.uprightDegrees)
    }

    @Test
    fun `ридер без геометрии — улик нет, и это не ошибка`() = runTest {
        val ans = File(tmp.root, "ans-flat.txt").apply { writeText("P=11004 Гречка 50") }
        var styled: List<DocBlock>? = null
        val flat = object : TextRecognizer {
            override suspend fun recognize(obj: PointObject) = "11004 Гречка 50"
        }

        val result = WordPlusRealizer(
            answering(ans), noPdf, docxSpy(File(tmp.root, "flat.docx")) { styled = it }, flat,
        ).perform(photo("24.jpg"), null)

        assertTrue(result is ActionResult.Success)
        assertTrue("пометка остаётся — она про доверие, а не про картинку", styled!!.single().uncertain)
        assertNull(styled!!.single().evidence)
    }
}
