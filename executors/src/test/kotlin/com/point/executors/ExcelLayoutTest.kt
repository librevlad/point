package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.BlockRole
import com.point.core.flow.CropEvidence
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.META_TABLE_COVERED
import com.point.core.flow.META_TABLE_FLAGGED
import com.point.core.flow.META_TABLE_GRID
import com.point.core.flow.META_TABLE_HEADER
import com.point.core.flow.META_TABLE_SCOPE
import com.point.core.flow.META_TABLE_UNREAD
import com.point.core.flow.ObjectStore
import com.point.core.flow.ReadingMode
import com.point.core.flow.SheetPlan
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.UNREAD_CAPTION
import com.point.core.flow.literalLayout
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Документ приходит в Excel блоками (#266).
 *
 * Ежедневная работа владельца — скан документа в Excel, где воспроизведена ВСЯ структура. Сегодня
 * шапка, реквизиты, подписи и примечания теряются молча: единственная допустимая форма ответа —
 * сетка, и остальному документу некуда лечь. Здесь проверяется, что ему есть куда лечь, что старый
 * ответ от этого не проигрывает и что непокрытое не молчит.
 */
class ExcelLayoutTest {

    private var lastPrompt: String? = null

    private fun answerOf(answer: String): ResultObject {
        val f = File.createTempFile("point-ans", ".txt").apply { deleteOnExit(); writeText(answer) }
        return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
    }

    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastPrompt = prompt
            return answerOf(answer)
        }
    }

    private var plan: SheetPlan? = null
    private val writer = object : SpreadsheetWriter {
        override suspend fun write(
            rows: List<List<String>>,
            candidates: Map<Pair<Int, Int>, List<String>>,
        ): ScratchRef =
            ScratchRef(File.createTempFile("point-xlsx", ".xlsx").apply { deleteOnExit() }.absolutePath)

        override suspend fun write(plan: SheetPlan): ScratchRef {
            this@ExcelLayoutTest.plan = plan
            return write(plan.rows, plan.candidates)
        }
    }

    private val noCrops = object : EvidenceCropper {
        override suspend fun crop(evidence: CropEvidence): EvidenceImage? = null
    }

    private val scratch = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String): PointObject = error("unused")
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("unused")
        override suspend fun put(result: ResultObject): PointObject = error("unused")
        override suspend fun children(collection: PointObject): List<PointObject> = emptyList()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) = ScratchRef(
            File.createTempFile("point-crop", ".$extension").apply { deleteOnExit() }.absolutePath,
        )
        override suspend fun clear() = Unit
    }

    private fun realizer(vararg answers: String) =
        ExcelRealizer(answers.map { llm(it) }, writer, noCrops, scratch)

    private fun atom(id: String, text: String, l: Float, t: Float, r: Float, b: Float) =
        Atom(id, text, Box(l, t, r, b))

    /** Счёт: заголовок, реквизит, сетка из двух строк, примечание. */
    private fun invoice(metadata: Map<String, String> = emptyMap()): PointObject {
        val layer = AtomLayer(
            listOf(
                atom("t1", "Рахунок", 10f, 10f, 100f, 30f),
                atom("t2", "№7", 105f, 10f, 140f, 30f),
                atom("f1", "Клієнт", 10f, 50f, 80f, 70f),
                atom("f2", "Термінал", 85f, 50f, 180f, 70f),
                atom("c1", "Товар", 10f, 100f, 90f, 120f),
                atom("c2", "Кіль-ть", 100f, 100f, 175f, 120f),
                atom("c3", "Гречка", 10f, 140f, 90f, 160f),
                atom("c4", "2", 100f, 140f, 120f, 160f),
                atom("n1", "Відпуск", 10f, 220f, 80f, 240f),
                atom("n2", "заборонено", 85f, 220f, 190f, 240f),
            ),
        )
        val dump = File.createTempFile("point-atoms", ".tsv").apply {
            deleteOnExit(); writeText(AtomCodec.encode(layer))
        }
        return PointObject(
            "id", "image/png", ScratchRef("/tmp/invoice.png"), ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_OCR_ATOMS_REF to dump.absolutePath) + metadata,
        )
    }

    private val wholePage =
        """{"scope":"full","blocks":[
             {"role":"title","ids":["t1","t2"]},
             {"role":"field","label":{"ids":["f1"]},"ids":["f2"]},
             {"role":"table","header":1,
              "rows":[[{"ids":["c1"]},{"ids":["c2"]}],[{"ids":["c3"]},{"ids":["c4"]}]]},
             {"role":"note","ids":["n1","n2"]}]}"""

    @Test
    fun `объект верхнего уровня разбирается — весь документ доезжает до файла`() = runTest {
        val result = realizer(wholePage).perform(invoice())

        assertTrue(result is ActionResult.Success)
        assertEquals(
            listOf(
                listOf("Рахунок №7"),
                listOf("Клієнт", "Термінал"),
                listOf("Товар", "Кіль-ть"),
                listOf("Гречка", "2"),
                listOf("Відпуск заборонено"),
            ),
            plan!!.rows,
        )
        assertEquals("шапка стоит на строке сетки, а не на заголовке документа", setOf(2), plan!!.headerRows)
    }

    @Test
    fun `факты о результате едут в метаданные`() = runTest {
        val result = realizer(wholePage).perform(invoice())

        val meta = (result as ActionResult.Success).result.metadata
        assertEquals("2×2", meta[META_TABLE_GRID])
        assertEquals("1", meta[META_TABLE_HEADER])
        assertEquals("документ целиком", meta[META_TABLE_SCOPE])
        assertEquals("да", meta[META_TABLE_COVERED])
        assertEquals("0", meta[META_TABLE_FLAGGED])
        assertNull("терять нечего — и ключа нет", meta[META_TABLE_UNREAD])
    }

    /**
     * Дословная приёмка среза: ничего видимого на странице не ушло в файл молча. Ответ назвал
     * только сетку — остальные слова страницы едут в файл своей частью, а не исчезают.
     */
    @Test
    fun `непокрытые слова доезжают до файла отдельной частью`() = runTest {
        val onlyTable =
            """{"blocks":[{"role":"table","header":1,
                 "rows":[[{"ids":["c1"]},{"ids":["c2"]}],[{"ids":["c3"]},{"ids":["c4"]}]]}]}"""

        val result = realizer(onlyTable).perform(invoice())

        val rows = plan!!.rows
        assertEquals(UNREAD_CAPTION, rows[2].single())
        assertEquals(
            listOf(listOf("Рахунок №7"), listOf("Клієнт Термінал"), listOf("Відпуск заборонено")),
            rows.drop(3),
        )
        val meta = (result as ActionResult.Success).result.metadata
        assertEquals("6", meta[META_TABLE_UNREAD])
        assertNull("нельзя обещать, что ничего не потеряно", meta[META_TABLE_COVERED])
    }

    /** Часть документа, названная метками, которых на странице нет, помечена — а не пропущена. */
    @Test
    fun `галлюцинированная часть документа помечена, а не пропущена`() = runTest {
        val answer =
            """{"blocks":[{"role":"title","ids":["w98","w99"]},
                 {"role":"table","header":1,
                  "rows":[[{"ids":["c1"]},{"ids":["c2"]}],[{"ids":["c3"]},{"ids":["c4"]}]]}]}"""

        realizer(answer).perform(invoice())

        assertTrue("пустой заголовок несёт пометку", plan!!.rows[0].single().contains('⚠'))
    }

    /** Вопрос о шапке решён числом: заголовков нет — и жирной лжи на первой строке тоже. */
    @Test
    fun `шапка, которой нет, не назначается первой строке`() = runTest {
        val answer =
            """{"blocks":[{"role":"table","header":0,
                 "rows":[[{"ids":["c3"]},{"ids":["c4"]}]]}]}"""

        val result = realizer(answer).perform(invoice())

        assertEquals(emptySet<Int>(), plan!!.headerRows)
        assertEquals("нет", (result as ActionResult.Success).result.metadata[META_TABLE_HEADER])
    }

    /** Документ без сетки — всё ещё документ: терять его молча хуже, чем отдать без таблицы. */
    @Test
    fun `ответ без сетки не пропадает — блоки доезжают до файла`() = runTest {
        val answer =
            """{"blocks":[{"role":"title","ids":["t1","t2"]},{"role":"note","ids":["n1","n2"]}]}"""

        val result = realizer(answer).perform(invoice())

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf("Рахунок №7"), plan!!.rows[0])
        assertNull("сетки не было — и размера её нет", (result as ActionResult.Success).result.metadata[META_TABLE_GRID])
    }

    // -- совместимость в обе стороны --

    @Test
    fun `массив по-прежнему таблица — и ничего вокруг него не выдумывается`() = runTest {
        val result = realizer("""[["Имя","Сумма"],["Приказ","42"]]""")
            .perform(PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE)))

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")), plan!!.rows)
        assertEquals("сегодняшний контракт — первая строка заголовок", setOf(0), plan!!.headerRows)
    }

    @Test
    fun `TSV не деградирует`() = runTest {
        realizer("Имя\tСумма\nПриказ\t42")
            .perform(PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE)))

        assertEquals(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")), plan!!.rows)
        assertEquals(setOf(0), plan!!.headerRows)
    }

    @Test
    fun `разбор понимает и объект, и массив, и TSV`() {
        val fromObject = parseLayout("""{"scope":"cropped","blocks":[{"role":"note","text":"Итого"}]}""")!!
        assertEquals(BlockRole.NOTE, fromObject.blocks.single().role)

        assertEquals(BlockRole.TABLE, parseLayout("""[["A","B"]]""")!!.blocks.single().role)
        assertEquals(BlockRole.TABLE, parseLayout("A\tB")!!.blocks.single().role)
        assertNull("пустой ответ таблицей не притворяется", parseLayout("[]"))
        assertNull(parseLayout("   "))
    }

    /** Роль — свидетельство, а не переключатель: незнакомое слово не повод потерять прочитанное. */
    @Test
    fun `часть с незнакомой ролью не выбрасывается`() {
        val layout = literalLayout(parseLayout("""{"blocks":[{"role":"footer","text":"стр. 1 из 2"}]}""")!!)

        assertEquals(BlockRole.NOTE, layout.blocks.single().role)
        assertEquals("стр. 1 из 2", layout.blocks.single().text)
    }

    // -- рукопись --

    /**
     * На рукописи правило «модель не трогает цифры» структурно неисполнимо — читателем была
     * зрячая модель. Честная пометка каждой цифры остаётся единственной заменой гарантии, и
     * режим чтения обязан доехать до результата: в scratch едут только метаданные результата.
     */
    @Test
    fun `рукопись помечает цифры и называет свой режим в результате`() = runTest {
        val page = PointObject(
            "id", "image/jpeg", ScratchRef("/tmp/hand.jpg"), ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_READING_MODE to ReadingMode.HANDWRITTEN.name),
        )

        val result = realizer("""{"blocks":[{"role":"title","text":"Відомість"},
            {"role":"table","header":1,"rows":[["Товар","Кіль-ть"],["Гречка","2"]]}]}""").perform(page)

        assertEquals("Відомість", plan!!.rows[0].single())
        assertEquals(listOf("Гречка", "2⚠"), plan!!.rows[2])
        val meta = (result as ActionResult.Success).result.metadata
        assertEquals(ReadingMode.HANDWRITTEN.name, meta[META_READING_MODE])
        assertEquals("ничто не притворяется прочитанным", "да", meta[META_TABLE_COVERED])
    }

    // -- запрос --

    @Test
    fun `запрос называет части документа, а не только сетку`() = runTest {
        realizer("""[["A","B"]]""")
            .perform(PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE)))

        val prompt = lastPrompt!!
        listOf("title", "field", "table", "totals", "note", "sign", "chrome", "unread").forEach {
            assertTrue("роль $it названа в запросе", prompt.contains("\"$it\""))
        }
        assertTrue("вопрос о шапке задан числом", prompt.contains("\"header\""))
        assertTrue("охват спрашивается", prompt.contains("\"scope\""))
        assertFalse(
            "требования пропустить нечитаемую строку больше нет",
            prompt.contains("пропусти её целиком"),
        )
    }
}
