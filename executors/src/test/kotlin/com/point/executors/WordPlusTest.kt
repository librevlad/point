package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.DocBlock
import com.point.core.flow.DocStyle
import com.point.core.flow.DocxWriter
import com.point.core.flow.FrameTransform
import com.point.core.flow.HANDWRITTEN_NOTE
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
        val cap = WordPlusCapability(aiKeysReady)
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

    /**
     * Уже прочитанное не добывается заново (#1031). Point читал кадр второй раз, другим путём,
     * получал текст хуже — с изменёнными цифрами товарных кодов — и строил документ человека
     * из него, дословно, как просит промпт.
     */
    @Test
    fun `прочитанное с кадра берётся из графа, а не читается заново`() = runTest {
        val read = File(tmp.root, "ocr.txt").apply {
            writeText("FAMILY DOLLAR\nCOCA COLA 1.25 LTR 049000055375")
        }
        val ans = File(tmp.root, "ans2.txt").apply { writeText("T=Чек\nB=049000055375") }
        val out = File(tmp.root, "doc2.docx").apply { writeText("zip") }
        val writer = object : DocxWriter {
            override suspend fun write(paragraphs: List<String>): ScratchRef = ScratchRef(out.absolutePath)
            override suspend fun writeStyled(blocks: List<DocBlock>): ScratchRef = ScratchRef(out.absolutePath)
        }
        var asked: String? = null
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                asked = prompt
                return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(ans.absolutePath))
            }
        }
        var reread = false
        val ocr = object : TextRecognizer {
            override suspend fun recognize(obj: PointObject): String {
                reread = true
                return "ОСА COLA 1.25 LTR 049000055975"
            }
        }
        val photo = PointObject(
            "img", "image/jpeg", ScratchRef("/tmp/chek.jpg"),
            ObjectState(ObjectKind.IMAGE),
            mapOf(com.point.core.flow.META_OCR_TEXT_REF to read.absolutePath),
        )

        val result = WordPlusRealizer(
            llm,
            object : PdfTextExtractor { override suspend fun extractText(obj: PointObject) = "" },
            writer,
            ocr,
        ).perform(photo, null)

        assertTrue(result is ActionResult.Success)
        assertFalse("кадр перечитан, хотя чтение уже есть в графе", reread)
        assertTrue("в документ ушли не те цифры: $asked", asked!!.contains("049000055375"))
    }

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

        assertEquals(listOf("Читаю текст PDF", "Размечаю документ", "Собираю документ"), heard)
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

        assertEquals(listOf("Размечаю документ", "Собираю документ"), heard)
    }

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

    private class Asked(val obj: PointObject, val prompt: String)

    private fun answering(answer: File, seen: (Asked) -> Unit = {}) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            seen(Asked(obj, prompt))
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(answer.absolutePath))
        }
    }

    private val noPdf = object : PdfTextExtractor { override suspend fun extractText(obj: PointObject) = "" }

    private fun photo(name: String, mode: ReadingMode? = ReadingMode.HANDWRITTEN) = PointObject(
        "id", "image/jpeg", ScratchRef(File(tmp.root, name).apply { writeText("пиксели") }.absolutePath),
        ObjectState(ObjectKind.IMAGE),
        metadata = mode?.let { mapOf(META_READING_MODE to it.name) }.orEmpty(),
    )

    private fun reading(layer: AtomLayer) =
        object : AtomRecognizer { override suspend fun read(obj: PointObject) = layer }

    private fun answer(name: String, text: String) = File(tmp.root, name).apply { writeText(text) }

    @Test
    fun `спорная строка фото уезжает в документ с адресом улики в сыром кадре`() = runTest {
        val ans = answer("ans-ev.txt", "T=Ведомость\nP=11004 Гречка 50")
        var styled: List<DocBlock>? = null
        val obj = photo("23.jpg")

        val result = WordPlusRealizer(
            answering(ans), noPdf, docxSpy(File(tmp.root, "ev.docx")) { styled = it }, reading(sheetLayer),
        ).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        val title = styled!!.first { it.text == "Ведомость" }
        val row = styled!!.first { it.text.startsWith("11004") }
        assertNull("заголовок без цифр — сверять нечего", title.evidence)
        assertNotNull("а помеченная строка знает своё место на бумаге", row.evidence)
        assertEquals(obj.uri.value, row.evidence!!.imagePath)
        assertEquals("кроп надо будет довернуть — файл лежит боком", 90, row.evidence!!.uprightDegrees)
    }

    @Test
    fun `ридер без геометрии — улик нет, и это не ошибка`() = runTest {
        val ans = answer("ans-flat.txt", "P=11004 Гречка 50")
        var styled: List<DocBlock>? = null
        val flat = object : TextRecognizer {
            override suspend fun recognize(obj: PointObject) = "11004 Гречка 50"
        }

        val result = WordPlusRealizer(
            answering(ans), noPdf, docxSpy(File(tmp.root, "flat.docx")) { styled = it }, flat,
        ).perform(photo("24.jpg"), null)

        assertTrue(result is ActionResult.Success)
        val row = styled!!.first { it.text.startsWith("11004") }
        assertTrue("пометка остаётся — она про доверие, а не про картинку", row.uncertain)
        assertNull(row.evidence)
    }

    private val soupLayer = AtomLayer(
        (0 until 8).map { Atom("g$it", "3}3/9I=I", Box(0f, it * 20f, 200f, it * 20f + 18f), confidence = 0.35f) },
    )

    private val printedLayer = AtomLayer(
        listOf(
            Atom("p0", "Ведомость", Box(0f, 0f, 120f, 20f), confidence = 0.9f),
            Atom("p1", "остатков", Box(130f, 0f, 240f, 20f), confidence = 0.9f),
            Atom("p2", "продовольствия", Box(0f, 30f, 200f, 50f), confidence = 0.88f),
            Atom("p3", "склада", Box(210f, 30f, 300f, 50f), confidence = 0.9f),
        ),
    )

    @Test
    fun `рукопись уходит модели снимком, а каша движка в запрос не попадает`() = runTest {
        val ans = answer("ans-hand.txt", "T=Недельный цикл\nB=Крупа 1450")
        var asked: Asked? = null
        val obj = photo("10.jpg", mode = null)

        val result = WordPlusRealizer(
            answering(ans) { asked = it }, noPdf,
            docxSpy(File(tmp.root, "hand.docx")) {}, reading(soupLayer),
        ).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        assertEquals("модели уехал снимок, а не подменыш-текст", "image/jpeg", asked!!.obj.mime)
        assertEquals(obj.uri.value, asked!!.obj.uri.value)
        assertEquals(WORD_PLUS_HANDWRITING_PROMPT, asked!!.prompt)
        assertFalse("пересказывать шум не о чем", asked!!.prompt.contains("3}3/9I=I"))
    }

    @Test
    fun `печатную страницу читает движок — модель её только размечает`() = runTest {
        val ans = answer("ans-print.txt", "T=Ведомость\nP=Итого 1450")
        var asked: Asked? = null
        var styled: List<DocBlock>? = null

        val result = WordPlusRealizer(
            answering(ans) { asked = it }, noPdf,
            docxSpy(File(tmp.root, "print.docx")) { styled = it }, reading(printedLayer),
        ).perform(photo("06.jpg", mode = null), null)

        assertTrue(result is ActionResult.Success)
        assertEquals("картинку модели не отдаём — цифры она видеть не должна", "text/plain", asked!!.obj.mime)
        assertTrue("прочитанное движком едет текстом", asked!!.prompt.contains("Ведомость остатков"))
        assertFalse("и документ ничем не подписан — это норма", styled!!.any { it.text.startsWith(HANDWRITTEN_NOTE) })
        assertFalse("печатные цифры чисты", styled!!.first { it.text.startsWith("Итого") }.uncertain)
    }

    @Test
    fun `движок не собрал ни слова — это не отказ, страницу читает модель`() = runTest {
        val ans = answer("ans-blank.txt", "T=Конспект\nP=Крупа 1450")
        var asked: Asked? = null
        var styled: List<DocBlock>? = null

        val result = WordPlusRealizer(
            answering(ans) { asked = it }, noPdf,
            docxSpy(File(tmp.root, "blank.docx")) { styled = it }, reading(AtomLayer(emptyList())),
        ).perform(photo("19.jpg", mode = null), null)

        assertTrue("«нет текста» здесь было бы отказом читать", result is ActionResult.Success)
        assertEquals("image/jpeg", asked!!.obj.mime)
        assertTrue("документ назван прочитанным по снимку", styled!!.first().text.startsWith(HANDWRITTEN_NOTE))
        assertTrue("и цифры в нём помечены", styled!!.first { it.text.startsWith("Крупа") }.uncertain)
    }

    @Test
    fun `на рукописи слышно, что модель читает страницу, а не размечает текст`() = runTest {
        val ans = answer("ans-stage.txt", "T=Конспект")
        val realizer = WordPlusRealizer(
            answering(ans), noPdf, docxSpy(File(tmp.root, "stage.docx")) {}, reading(soupLayer),
        )

        val heard = stagesHeard { realizer.perform(photo("10b.jpg", mode = null), null) }

        assertEquals(listOf("Распознаю текст на фото", "Читаю страницу", "Собираю документ"), heard)
    }

    @Test
    fun `модель не разобрала страницу — отказ говорит про чтение, а не про разметку`() = runTest {
        val ans = answer("ans-junk.txt", "не смогла разобрать эту страницу")

        val result = WordPlusRealizer(
            answering(ans), noPdf, docxSpy(File(tmp.root, "junk.docx")) {}, reading(soupLayer),
        ).perform(photo("19b.jpg", mode = null), null)

        assertEquals("Не удалось прочитать страницу", (result as ActionResult.Failure).reason)
    }

    @Test
    fun `правка ручкой поверх печати едет в документ обеими версиями и помеченной`() = runTest {
        val ans = answer("ans-strike.txt", "P=Крупа гречневая ~~53~~ 40")
        var styled: List<DocBlock>? = null

        WordPlusRealizer(
            answering(ans), noPdf, docxSpy(File(tmp.root, "strike.docx")) { styled = it }, reading(printedLayer),
        ).perform(photo("23b.jpg", mode = null), null)

        val row = styled!!.first()
        assertEquals("обе версии дословно — выбирает человек", "Крупа гречневая ~~53~~ 40", row.text)
        assertTrue("и правка помечена, хотя страница печатная", row.uncertain)
    }
}
