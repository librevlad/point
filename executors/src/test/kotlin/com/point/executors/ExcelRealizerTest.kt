package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.SpreadsheetWriter
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The realizer parses the LLM's TSV into rows and hands them to the writer. */
class ExcelRealizerTest {

    private var lastPrompt: String? = null

    // The real LlmClient writes its answer to scratch and returns a ResultObject to it.
    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastPrompt = prompt
            val f = File.createTempFile("point-ans", ".txt").apply { deleteOnExit(); writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private var lastRows: List<List<String>>? = null
    private var lastCandidates: Map<Pair<Int, Int>, List<String>> = emptyMap()
    private val writer = object : SpreadsheetWriter {
        override suspend fun write(
            rows: List<List<String>>,
            candidates: Map<Pair<Int, Int>, List<String>>,
        ): ScratchRef {
            lastRows = rows
            lastCandidates = candidates
            return ScratchRef(File.createTempFile("point-xlsx", ".xlsx").apply { deleteOnExit() }.absolutePath)
        }
    }

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    /** A realizer over one or more canned model reads (consensus votes across them). */
    private fun realizer(vararg answers: String) = ExcelRealizer(answers.map { llm(it) }, writer)

    @Test
    fun `parses TSV into rows and produces an OFFICE xlsx`() = runTest {
        val result = realizer("Имя\tСумма\nПриказ\t42").perform(image)
        assertTrue(result is ActionResult.Success)
        assertEquals(ObjectKind.OFFICE, (result as ActionResult.Success).result.type)
        assertEquals(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")), lastRows)
    }

    @Test
    fun `tolerates a code fence the model may wrap around the TSV`() = runTest {
        val result = realizer("```tsv\nA\tB\n1\t2\n```").perform(image)
        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), lastRows)
    }

    @Test
    fun `a blank answer surfaces a recoverable failure`() = runTest {
        val result = realizer("   ").perform(image)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `parses a structured JSON table (issue 22)`() = runTest {
        val result = realizer("""[["Имя","Сумма"],["Приказ","42"]]""").perform(image)
        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("Имя", "Сумма"), listOf("Приказ", "42")), lastRows)
    }

    @Test
    fun `tolerates a json code fence`() = runTest {
        realizer("```json\n[[\"A\",\"B\"],[\"1\",\"2\"]]\n```").perform(image)
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), lastRows)
    }

    @Test
    fun `two models that disagree flag the cell (#200 consensus)`() = runTest {
        realizer(
            """[["№","Сума"],["1","42"]]""",
            """[["№","Сума"],["1","43"]]""",
        ).perform(image)
        assertEquals("42⚠", lastRows!![1][1]) // disagreement → plurality value, flagged for review
        assertEquals(listOf("№", "Сума"), lastRows!![0]) // agreed header is clean
        assertEquals(listOf("42", "43"), lastCandidates[1 to 1]) // both readings offered as candidates
    }

    @Test
    fun `falls back to TSV when the model answers in the old delimited format`() {
        // parseTable prefers JSON but keeps working on plain TSV.
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), parseTable("A\tB\n1\t2"))
        assertEquals(emptyList<List<String>>(), parseTable("   "))
    }

    // -- #258: модель указывает на слова страницы, текст собирается из атомов --

    /** Дословный трек с посылочного экрана, тремя кусками, плюс подпись строкой выше. */
    private fun imageWithAtoms(): PointObject {
        val layer = AtomLayer(
            listOf(
                Atom("h1", "Трек-номер", Box(10f, 60f, 120f, 80f)),
                Atom("a1", "20", Box(10f, 100f, 40f, 120f)),
                Atom("a2", "4514 9154", Box(45f, 100f, 140f, 120f)),
                Atom("a3", "9395", Box(145f, 100f, 190f, 120f)),
            ),
        )
        val dump = File.createTempFile("point-atoms", ".tsv").apply {
            deleteOnExit(); writeText(AtomCodec.encode(layer))
        }
        return PointObject(
            "id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_OCR_ATOMS_REF to dump.absolutePath),
        )
    }

    @Test
    fun `ячейка из меток собирается из слов страницы, а не из пересказа модели`() = runTest {
        val result = realizer("""[[{"ids":["h1"]},{"ids":["a1","a2","a3"]}]]""").perform(imageWithAtoms())

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("Трек-номер", "20 4514 9154 9395")), lastRows)
        assertTrue(lastPrompt!!.contains("[a2]4514 9154")) // индекс слов приложен к запросу
    }

    @Test
    fun `выдуманная метка не проходит молча — ячейка помечена`() = runTest {
        realizer("""[[{"ids":["a1","ghost","a2","a3"]}]]""").perform(imageWithAtoms())

        assertEquals("20 4514 9154 9395⚠", lastRows!![0][0])
    }

    @Test
    fun `тронутая моделью цифра уходит в спор — оба чтения в дропдауне, ячейка помечена`() = runTest {
        realizer("""[[{"ids":["a1","a2","a3"],"text":"20 4614 9154 9395"}]]""").perform(imageWithAtoms())

        assertEquals("20 4514 9154 9395⚠", lastRows!![0][0])
        assertEquals(listOf("20 4514 9154 9395", "20 4614 9154 9395"), lastCandidates[0 to 0])
    }

    @Test
    fun `модель, ответившая по-старому текстом, не ломает путь с индексом`() = runTest {
        val result = realizer("""[["Трек-номер","20 4514 9154 9395"]]""").perform(imageWithAtoms())

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("Трек-номер", "20 4514 9154 9395")), lastRows)
    }

    @Test
    fun `без слоя слов запрос остаётся прежним — рукопись модель читает своими глазами`() = runTest {
        realizer("""[["A","B"]]""").perform(image)

        assertFalse(lastPrompt!!.contains("метк")) // ни индекса, ни правил про метки
        assertEquals(listOf(listOf("A", "B")), lastRows)
    }

    @Test
    fun `битый дамп слоя не роняет действие — работает старый контракт`() = runTest {
        val broken = File.createTempFile("point-atoms", ".tsv").apply { deleteOnExit(); writeText("мусор") }
        val obj = PointObject(
            "id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_OCR_ATOMS_REF to broken.absolutePath),
        )

        val result = realizer("""[["A","B"]]""").perform(obj)

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("A", "B")), lastRows)
    }

    @Test
    fun `разбор адресного ответа терпит числовые метки и пустые объекты`() {
        val cells = parseAddressedCells("""[[{"ids":[1,2]},{},{"text":"от руки"},"просто текст"]]""")!!

        assertEquals(
            listOf(
                listOf(
                    com.point.core.flow.CellAnswer.Ids(listOf("1", "2")),
                    com.point.core.flow.CellAnswer.Literal(""),
                    com.point.core.flow.CellAnswer.Literal("от руки"),
                    com.point.core.flow.CellAnswer.Literal("просто текст"),
                ),
            ),
            cells,
        )
    }
}
