package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

class ExcelRealizerTest {

    private var lastPrompt: String? = null

    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastPrompt = prompt
            return answerOf(answer)
        }
    }

    private fun answerOf(answer: String): ResultObject {
        val f = File.createTempFile("point-ans", ".txt").apply { deleteOnExit(); writeText(answer) }
        return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
    }

    private var lastRows: List<List<String>>? = null
    private var lastCandidates: Map<Pair<Int, Int>, List<String>> = emptyMap()
    private var lastPlan: SheetPlan? = null
    private val writer = object : SpreadsheetWriter {
        override suspend fun write(
            rows: List<List<String>>,
            candidates: Map<Pair<Int, Int>, List<String>>,
        ): ScratchRef {
            lastRows = rows
            lastCandidates = candidates
            return ScratchRef(File.createTempFile("point-xlsx", ".xlsx").apply { deleteOnExit() }.absolutePath)
        }

        override suspend fun write(plan: SheetPlan): ScratchRef {
            lastPlan = plan
            return write(plan.rows, plan.candidates)
        }
    }

    private fun gridRows(): List<List<String>> =
        lastRows!!.takeWhile { it.firstOrNull() != UNREAD_CAPTION }

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    private val noCrops = object : EvidenceCropper {
        override suspend fun crop(evidence: CropEvidence): EvidenceImage? = null
    }

    /** Страницы набора, как их отдаёт хранилище (#1207). */
    private var pages: List<PointObject> = emptyList()

    private val scratch = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String): PointObject = error("unused")
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ): PointObject = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent(pages, pages.size)
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) = ScratchRef(
            File.createTempFile("point-crop", ".$extension").apply { deleteOnExit() }.absolutePath,
        )
        override suspend fun clear() = Unit
    }

    private fun realizer(vararg answers: String) =
        ExcelRealizer(answers.map { llm(it) }, writer, noCrops, scratch, testKnowledge())

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
        assertEquals("42⚠", lastRows!![1][1])
        assertEquals(listOf("№", "Сума"), lastRows!![0])
        assertEquals(listOf("42", "43"), lastCandidates[1 to 1])
    }

    @Test
    fun `falls back to TSV when the model answers in the old delimited format`() {

        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), parseTable("A\tB\n1\t2"))
        assertEquals(emptyList<List<String>>(), parseTable("   "))
    }

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

        assertTrue(lastPrompt!!.contains("[a2 rule=track-shaped]4514 9154"))
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

        assertFalse(lastPrompt!!.contains("метк"))
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

    @Test
    fun `метки строкой и голым массивом не теряются`() {
        val cells = parseAddressedCells("""[[{"ids":"h1"},["a1","a2"]]]""")!!

        assertEquals(
            listOf(
                listOf(
                    com.point.core.flow.CellAnswer.Ids(listOf("h1")),
                    com.point.core.flow.CellAnswer.Ids(listOf("a1", "a2")),
                ),
            ),
            cells,
        )
    }

    @Test
    fun `метка строкой доезжает до текста ячейки из атомов`() = runTest {
        realizer("""[[{"ids":"h1"},{"ids":"a1"}]]""").perform(imageWithAtoms())

        assertEquals(listOf(listOf("Трек-номер", "20")), gridRows())
    }

    @Test
    fun `метка, процитированная вместе с атрибутом rule, не теряется`() = runTest {
        realizer("""[[{"ids":["a1 rule=track-shaped","a2 rule=track-shaped","a3 rule=track-shaped"]}]]""")
            .perform(imageWithAtoms())

        assertEquals(listOf(listOf("20 4514 9154 9395")), gridRows())
    }

    @Test
    fun `явный null в тексте ячейки — не чтение модели`() = runTest {
        realizer("""[[{"ids":["a1"],"text":null}]]""").perform(imageWithAtoms())

        assertEquals(listOf(listOf("20")), gridRows())
        assertTrue(lastCandidates.isEmpty())
    }

    @Test
    fun `полностью галлюцинированная таблица — отказ, а не пустой успех`() = runTest {
        val result = realizer("""[[{"ids":[1]},{"ids":[2]}],[{"ids":[3]},{"ids":[4]}]]""")
            .perform(imageWithAtoms())

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `одна галлюцинированная ячейка помечается, таблица живёт`() = runTest {
        realizer("""[[{"ids":["w99"]},{"ids":["a1"]}]]""").perform(imageWithAtoms())

        assertEquals(listOf(listOf("⚠", "20")), gridRows())
    }

    @Test
    fun `диктовка цифры мимо страницы видна в xlsx`() = runTest {
        realizer("""[[{"ids":["h1"]},"1600"]]""").perform(imageWithAtoms())

        assertEquals(listOf(listOf("Трек-номер", "1600⚠")), gridRows())
    }

    @Test
    fun `диктовка мимо страницы переживает голосование двух моделей`() = runTest {
        realizer("Трек-номер\t1600", """[[{"ids":["h1"]},"1600"]]""").perform(imageWithAtoms())

        assertEquals("1600⚠", lastRows!![0][1])
    }

    @Test
    fun `порядок ответов моделей не решает судьбу пометки`() = runTest {
        realizer("""[[{"ids":["h1"]},"1600"]]""", "Трек-номер\t1600").perform(imageWithAtoms())

        assertEquals("1600⚠", lastRows!![0][1])
    }

    @Test
    fun `разорванная ячейка не побеждает голосование и не лезет в дропдаун`() = runTest {
        realizer("""[[{"ids":["zz"]},{"ids":["h1"]}]]""", """[[{"ids":["a1"]},{"ids":["h1"]}]]""")
            .perform(imageWithAtoms())

        assertEquals(listOf(listOf("20", "Трек-номер")), gridRows())
        assertTrue(lastCandidates.isEmpty())
    }

    @Test
    fun `сплющенный адресный ответ читается как строка ячеек, а не как текст`() = runTest {
        realizer("""[{"ids":["h1"]},{"ids":["a1","a2","a3"]}]""").perform(imageWithAtoms())

        assertEquals(listOf(listOf("Трек-номер", "20 4514 9154 9395")), lastRows)
    }

    @Test
    fun `TSV-ответ при живом слое не минует проверку диктовки`() = runTest {
        realizer("Итого\t1600").perform(imageWithAtoms())

        assertEquals(listOf(listOf("Итого", "1600⚠")), lastRows)
    }

    @Test
    fun `два чтения идут одновременно, а не одно за другим`() = runTest {
        val started = List(2) { CompletableDeferred<Unit>() }
        fun paired(i: Int, answer: String) = object : LlmClient {
            override val strongVision = true
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                started[i].complete(Unit)
                withTimeout(STUCK_MS) { started[1 - i].await() }
                return answerOf(answer)
            }
        }

        val result = ExcelRealizer(
            listOf(paired(0, """[["№","42"]]"""), paired(1, """[["№","42"]]""")),
            writer, noCrops, scratch, testKnowledge(),
        ).perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("2", (result as ActionResult.Success).result.metadata["models"])
    }

    @Test
    fun `отказ одной модели не роняет чтение соседней`() = runTest {
        val broken = object : LlmClient {
            override val strongVision = true
            override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("HTTP 429 quota")
        }

        val result = ExcelRealizer(listOf(broken, llm("""[["A","B"]]""")), writer, noCrops, scratch, testKnowledge())
            .perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("A", "B")), lastRows)
    }

    @Test
    fun `порядок чтений — порядок моделей, а не порядок финиша`() = runTest {
        val fastAnswered = CompletableDeferred<Unit>()
        val strongButSlow = object : LlmClient {
            override val strongVision = true
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                withTimeout(STUCK_MS) { fastAnswered.await() }
                return answerOf("""[["№","42"]]""")
            }
        }
        val quick = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject =
                answerOf("""[["№","43"]]""").also { fastAnswered.complete(Unit) }
        }

        ExcelRealizer(listOf(strongButSlow, quick), writer, noCrops, scratch, testKnowledge()).perform(image)

        assertEquals("42⚠", lastRows!![0][1])
        assertEquals(listOf("42", "43"), lastCandidates[0 to 1])
    }

    @Test
    fun `отказ модели не съедает слот — он берёт следующего кандидата`() = runTest {
        val keyless = object : LlmClient {
            override val strongVision = true
            override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("задайте свой ключ")
        }
        val bothRead = CompletableDeferred<Unit>()
        val started = ConcurrentLinkedQueue<String>()
        fun waiting(answer: String) = object : LlmClient {
            override val strongVision = true
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                started += answer
                if (started.size == 2) bothRead.complete(Unit)
                withTimeout(STUCK_MS) { bothRead.await() }
                return answerOf(answer)
            }
        }
        var result: ActionResult? = null

        val heard = stagesHeard {
            result = ExcelRealizer(
                listOf(keyless, waiting("""[["№","42"]]"""), waiting("""[["№","42"]]""")),
                writer, noCrops, scratch, testKnowledge(),
            ).perform(image)
        }

        assertTrue(result is ActionResult.Success)
        assertEquals("2", (result as ActionResult.Success).result.metadata["models"])
        assertTrue(heard.contains("Не вышло — пробую другим путём"))
    }

    @Test
    fun `когда чтения не дали таблиц, читают следующие модели`() = runTest {
        var result: ActionResult? = null

        val heard = stagesHeard {
            result = ExcelRealizer(
                listOf(llm("   "), llm("   "), llm("""[["A","B"]]""")),
                writer, noCrops, scratch, testKnowledge(),
            ).perform(image)
        }

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("A", "B")), lastRows)
        assertTrue(heard.contains("Перечитываю другим путём"))
    }

    @Test
    fun `стадии не выдают одновременное чтение за очередь`() = runTest {
        val heard = stagesHeard {
            realizer("""[["№","42"]]""", """[["№","42"]]""").perform(image)
        }

        assertTrue(heard.contains("Таблицу читают 2 модели одновременно"))
        assertTrue(heard.contains("Готово 1 из 2 чтений — жду остальные"))
        assertTrue(heard.none { it.startsWith("Модель ") })
    }

    @Test
    fun `с единственной моделью стадия не считает несуществующих соседей`() = runTest {
        val heard = stagesHeard { realizer("""[["A","B"]]""").perform(image) }

        assertTrue(heard.contains("Читаю таблицу"))
        assertTrue(heard.none { it.contains("из 1") })
    }

    @Test
    fun `ожидание двух чтений — это самое медленное чтение, а не их сумма`() = runTest {
        val spans = ConcurrentLinkedQueue<Pair<Long, Long>>()
        fun slow(answer: String) = object : LlmClient {
            override val strongVision = true
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                val from = System.currentTimeMillis()
                delay(READ_MS)
                spans += from to System.currentTimeMillis()
                return answerOf(answer)
            }
        }
        val realizer =
            ExcelRealizer(listOf(slow("""[["№","42"]]"""), slow("""[["№","42"]]""")), writer, noCrops, scratch, testKnowledge())

        val result = realizer.perform(image)

        assertTrue(result is ActionResult.Success)
        val queued = spans.sumOf { it.second - it.first }
        val together = spans.maxOf { it.second } - spans.minOf { it.first }
        println("два чтения по $READ_MS мс — по очереди $queued мс, одновременно $together мс")
        assertTrue("ждали $together мс против $queued мс по очереди", together * 4 < queued * 3)
    }

    @Test
    fun `спор о цифре не приклеивается к чужой ячейке при сдвиге строк`() = runTest {
        realizer(
            """[["Трек-номер"],["20 4514 9154 9395"]]""",
            """[[{"ids":["a1","a2","a3"],"text":"20 4614 9154 9395"}]]""",
        ).perform(imageWithAtoms())

        assertEquals("20 4514 9154 9395⚠", lastRows!![1][0])
        assertEquals(listOf("20 4514 9154 9395", "20 4614 9154 9395"), lastCandidates[1 to 0])
        assertTrue(lastCandidates[0 to 0].orEmpty().none { it.contains("4614") })
    }

    @Test
    fun `промпт требует оба значения, когда рука дописана поверх печати (#345)`() = runTest {
        val r = realizer("[[\"Арт.\",\"До видачі\"],[\"11025\",\"1,0 0,230\"]]")

        r.perform(image)

        val prompt = lastPrompt!!
        assertTrue("правило «оба значения» названо", prompt.contains("Верни ОБА"))
        assertTrue("замена запрещена прямо", prompt.contains("заменяет напечатанное"))

        assertEquals("1,0 0,230", lastRows!![1][1])
    }

    private fun sheetImage(): PointObject {
        val layer = AtomLayer(
            listOf(
                Atom("s1", "11004", Box(0f, 100f, 80f, 120f)),
                Atom("s2", "Гречка", Box(100f, 100f, 200f, 120f)),
                Atom("s3", "0,120", Box(300f, 100f, 380f, 120f)),
                Atom("s4", "11006", Box(0f, 200f, 80f, 220f)),
                Atom("s5", "Рис", Box(100f, 200f, 160f, 220f)),
                Atom("s6", "0,500", Box(300f, 200f, 380f, 220f)),
            ),
        )
        val dump = File.createTempFile("point-atoms", ".tsv").apply {
            deleteOnExit(); writeText(AtomCodec.encode(layer))
        }
        return PointObject(
            "id", "image/png", ScratchRef("/tmp/sheet.png"), ObjectState(ObjectKind.IMAGE),
            metadata = mapOf(META_OCR_ATOMS_REF to dump.absolutePath),
        )
    }

    private class RecordingCropper : EvidenceCropper {
        var seen: CropEvidence? = null
        override suspend fun crop(evidence: CropEvidence): EvidenceImage? {
            seen = evidence
            return EvidenceImage(byteArrayOf(7), 8, 8)
        }
    }

    private fun eyes(table: String, cell: String) = object : LlmClient {
        override val strongVision = true
        override suspend fun run(obj: PointObject, prompt: String): ResultObject =
            answerOf(if ("Варианты чтения" in prompt) cell else table)
    }

    private val tableA = """[["11004","Гречка","0,120"],["11006","Рис","0,500"]]"""
    private val tableB = """[["11004","Гречка","0,125"],["11006","Рис","0,500"]]"""

    @Test
    fun `согласный перечит кропом снимает спор — ячейка чистая, дропдауна нет (#346)`() = runTest {
        val cropper = RecordingCropper()
        var result: ActionResult? = null

        val heard = stagesHeard {
            result = ExcelRealizer(
                listOf(eyes(tableA, "0,120"), eyes(tableB, "0,120")), writer, cropper, scratch, testKnowledge(),
            ).perform(sheetImage())
        }

        assertTrue(result is ActionResult.Success)
        assertEquals("0,120", lastRows!![0][2])
        assertTrue(lastCandidates.isEmpty())
        assertEquals("кроп режется из исходного кадра", "/tmp/sheet.png", cropper.seen!!.imagePath)

        assertEquals(CropPurpose.READING, cropper.seen!!.purpose)
        assertTrue(heard.contains("Переспрашиваю 1 спорную ячейку"))
    }

    @Test
    fun `несогласный перечит спор не гасит — третье чтение встаёт в дропдаун (#346)`() = runTest {
        val result = ExcelRealizer(
            listOf(eyes(tableA, "0,999"), eyes(tableB, "0,999")), writer, RecordingCropper(), scratch, testKnowledge(),
        ).perform(sheetImage())

        assertTrue(result is ActionResult.Success)
        assertEquals("0,120⚠", lastRows!![0][2])
        assertEquals(listOf("0,120", "0,125⚠", "0,999"), lastCandidates[0 to 2])
    }

    @Test
    fun `перечит не успел в общий срок — действие не падает, спор остаётся (#346)`() = runTest {
        fun sleepy(table: String) = object : LlmClient {
            override val strongVision = true
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                if ("Варианты чтения" in prompt) awaitCancellation()
                return answerOf(table)
            }
        }

        val result = ExcelRealizer(
            listOf(sleepy(tableA), sleepy(tableB)), writer, RecordingCropper(), scratch, testKnowledge(),
            recropTimeoutMs = 200,
        ).perform(sheetImage())

        assertTrue(result is ActionResult.Success)
        assertEquals("0,120⚠", lastRows!![0][2])
        assertEquals(listOf("0,120", "0,125⚠"), lastCandidates[0 to 2])
    }

    @Test
    fun `спорной ячейке без места на кадре кроп не режется — спор остаётся человеку (#346)`() = runTest {

        val cropper = RecordingCropper()
        val a = """[["11004","Гречка","0,120"],["11006","Рис","0,500"],["Разом","до видачі","2400"]]"""
        val b = """[["11004","Гречка","0,120"],["11006","Рис","0,500"],["Разом","до видачі","2100"]]"""

        val result = ExcelRealizer(
            listOf(eyes(a, "2400"), eyes(b, "2400")), writer, cropper, scratch, testKnowledge(),
        ).perform(sheetImage())

        assertTrue(result is ActionResult.Success)
        assertNull("приложить соседнюю строку хуже, чем ничего", cropper.seen)
        assertEquals(listOf("2400⚠", "2100⚠"), lastCandidates[2 to 2])
    }

    @Test
    fun `пустой JSON-ответ — честный отказ, а не ячейка со скобками`() = runTest {
        assertTrue(parseTable("[]").isEmpty())
        assertTrue(parseTable("[ ]").isEmpty())
        assertTrue("обломанный JSON — тоже не TSV", parseTable("[[\"А\",").isEmpty())

        val result = realizer("[]").perform(image)

        assertTrue("пустой ответ — отказ действия", result is ActionResult.Failure)
    }

    // ---- Набор снимков — одна таблица по порядку страниц (#1207) ----

    private fun page(name: String) = PointObject(
        name, "image/jpeg", ScratchRef("/tmp/$name"), ObjectState(ObjectKind.IMAGE), mapOf("name" to name),
    )

    private val set = PointObject("set", "inode/directory", ScratchRef("/tmp/set"), ObjectState(ObjectKind.COLLECTION))

    /** Модель, читающая каждую страницу по-своему — по имени снимка; неназванная страница ей не даётся. */
    private fun readerByPage(answers: Map<String, String>) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            val name = obj.metadata["name"]
            return answerOf(answers[name] ?: error("снимок $name не разобрать"))
        }
    }

    @Test
    fun `«В Excel» принимает набор снимков`() {
        assertTrue(ExcelCapability { true }.accepts(ObjectState(ObjectKind.COLLECTION)))
    }

    @Test
    fun `две страницы набора сшиваются в одну таблицу в порядке набора`() = runTest {
        pages = listOf(page("IMG_1.jpg"), page("IMG_2.jpg"))
        val reader = readerByPage(
            mapOf(
                "IMG_1.jpg" to """[["Товар","Кол-во"],["Гречка","2"]]""",
                "IMG_2.jpg" to """[["Товар","Кол-во"],["Соль","3"]]""",
            ),
        )
        val reordered = set.copy(
            metadata = mapOf(META_COLLECTION_ORDER to collectionOrderValue(listOf("IMG_2.jpg", "IMG_1.jpg"))),
        )

        val result = ExcelRealizer(listOf(reader), writer, noCrops, scratch, testKnowledge()).perform(reordered)

        assertTrue(result is ActionResult.Success)
        assertEquals(
            listOf(listOf("Товар", "Кол-во"), listOf("Соль", "3"), listOf("Гречка", "2")),
            lastRows,
        )
        val meta = (result as ActionResult.Success).result.metadata
        assertEquals("2", meta[META_TABLE_PAGES])
        assertNull(meta[META_TABLE_PAGES_UNREAD])
        assertEquals(ObjectKind.OFFICE, result.result.type)
    }

    @Test
    fun `без знания о порядке страницы идут по имени файла`() = runTest {
        pages = listOf(page("IMG_2.jpg"), page("IMG_1.jpg"))
        val reader = readerByPage(
            mapOf(
                "IMG_1.jpg" to """[["A"],["первая"]]""",
                "IMG_2.jpg" to """[["A"],["вторая"]]""",
            ),
        )

        ExcelRealizer(listOf(reader), writer, noCrops, scratch, testKnowledge()).perform(set)

        assertEquals(listOf(listOf("A"), listOf("первая"), listOf("вторая")), lastRows)
    }

    @Test
    fun `страница, которую не прочитать, не выбрасывается молча — её место помечено`() = runTest {
        pages = listOf(page("IMG_1.jpg"), page("IMG_2.jpg"), page("IMG_3.jpg"))
        val reader = readerByPage(
            mapOf(
                "IMG_1.jpg" to """[["Товар","Кол-во"],["Гречка","2"]]""",
                "IMG_3.jpg" to """[["Товар","Кол-во"],["Соль","3"]]""",
            ),
        )

        val result = ExcelRealizer(listOf(reader), writer, noCrops, scratch, testKnowledge()).perform(set)

        assertTrue(result is ActionResult.Success)
        val rows = lastRows!!
        assertEquals(listOf("Гречка", "2"), rows[1])
        val gap = rows[2].single()
        assertTrue("место второй страницы помечено: $gap", gap.contains('⚠') && gap.contains("2 из 3"))
        assertFalse("сырые слова модели в файл человека не попадают", gap.contains("не разобрать"))
        assertEquals(listOf("Соль", "3"), rows[3])
        assertEquals("1", (result as ActionResult.Success).result.metadata[META_TABLE_PAGES_UNREAD])
    }

    @Test
    fun `страницы-тексты набора читаются так же, как снимки`() = runTest {
        fun textPage(name: String, text: String): PointObject {
            val f = File.createTempFile("point-page", ".txt").apply { deleteOnExit(); writeText(text) }
            return PointObject(name, "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT), mapOf("name" to name))
        }
        pages = listOf(textPage("2.txt", "вторая"), textPage("1.txt", "первая"))
        val reader = readerByPage(mapOf("1.txt" to """[["A"],["первая"]]""", "2.txt" to """[["A"],["вторая"]]"""))

        val result = ExcelRealizer(listOf(reader), writer, noCrops, scratch, testKnowledge()).perform(set)

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("A"), listOf("первая"), listOf("вторая")), lastRows)
    }

    @Test
    fun `не прочиталась ни одна страница — отказ, а не пустой файл`() = runTest {
        pages = listOf(page("IMG_1.jpg"), page("IMG_2.jpg"))

        val result = realizer("   ").perform(set)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("2 страниц"))
        assertNull("файл не писался", lastPlan)
    }

    // ---- #1243: у набора есть названный предел страниц, и файл доходит до человека ----

    @Test
    fun `набор длиннее предела читается до предела, а не пропадает целиком (#1243)`() = runTest {
        val over = 3
        pages = (1..MAX_TABLE_PAGES + over).map { page("IMG_$it.jpg") }
        var asked = 0
        val reader = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                asked++
                return answerOf("""[["Товар"],["Гречка"]]""")
            }
        }

        var result: ActionResult? = null
        stagesHeard {
            result = ExcelRealizer(listOf(reader), writer, noCrops, scratch, testKnowledge()).perform(set)
        }

        assertEquals("облако спрошено про страницы за пределом", MAX_TABLE_PAGES, asked)
        assertTrue("прочитанные страницы пропали вместе с файлом", result is ActionResult.Success)
        val meta = (result as ActionResult.Success).result.metadata
        assertEquals((MAX_TABLE_PAGES + over).toString(), meta[META_TABLE_PAGES])
        assertEquals("непрочитанные страницы не сосчитаны", over.toString(), meta[META_TABLE_PAGES_UNREAD])
        val tail = lastRows!!.last().single()
        assertTrue(
            "страницы за пределом пропали из файла молча: $tail",
            tail.contains('⚠') && tail.contains("${MAX_TABLE_PAGES + 1}–${MAX_TABLE_PAGES + over}") &&
                tail.contains("$MAX_TABLE_PAGES"),
        )
        assertEquals(
            "на каждую непрочитанную страницу пришлось по строке-предупреждению",
            1,
            lastRows!!.count { it.size == 1 && it.single().contains("не читал") },
        )
    }

    @Test
    fun `до первого облачного вызова сказано, сколько страниц будет прочитано (#1243)`() = runTest {
        pages = (1..MAX_TABLE_PAGES + 2).map { page("IMG_$it.jpg") }

        val heard = stagesHeard {
            ExcelRealizer(listOf(llm("""[["Товар"],["Гречка"]]""")), writer, noCrops, scratch, testKnowledge())
                .perform(set)
        }

        assertEquals(
            "цена захода названа задним числом",
            pagesAheadStage(MAX_TABLE_PAGES, MAX_TABLE_PAGES + 2),
            heard.firstOrNull(),
        )
        assertTrue(
            "человеку не названы ни сколько прочтётся, ни сколько всего: ${heard.first()}",
            "$MAX_TABLE_PAGES" in heard.first() && "${MAX_TABLE_PAGES + 2}" in heard.first(),
        )
    }

    @Test
    fun `набор в пределах предела о пределе не говорит, но страницы называет (#1243)`() = runTest {
        pages = listOf(page("IMG_1.jpg"), page("IMG_2.jpg"))

        val heard = stagesHeard {
            ExcelRealizer(listOf(llm("""[["Товар"],["Гречка"]]""")), writer, noCrops, scratch, testKnowledge())
                .perform(set)
        }

        assertEquals("цена захода не названа до первого чтения", pagesAheadStage(2, 2), heard.first())
        assertTrue("сколько страниц читается — не сказано: ${heard.first()}", "2" in heard.first())
        assertFalse(
            "человеку назван предел, до которого ему далеко: ${heard.first()}",
            "$MAX_TABLE_PAGES" in heard.first(),
        )
    }

    /**
     * #1243, решение владельца: «при пределе/обрыве — честная остановка с частичным файлом».
     *
     * Сколько минут уйдёт на страницу, решают чужие бесплатные провайдеры, а не Point: предела
     * страниц мало. Заход, читающий по минуте с четвертью на страницу, упирался в потолок
     * действия — а отменённое действие не отдаёт ничего: файла нет, квота на уже прочитанные
     * страницы потрачена. Заход обязан кончиться сам и отдать прочитанное.
     */
    @Test
    fun `медленный набор кончается сам внутри потолка действия и отдаёт прочитанное (#1243)`() = runTest {
        pages = (1..MAX_TABLE_PAGES).map { page("IMG_$it.jpg") }
        var spentMs = 0L
        val perPageMs = ACTION_CEILING_MS / 8
        val slow = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                spentMs += perPageMs
                return answerOf("""[["Товар"],["Гречка"]]""")
            }
        }

        val result = ExcelRealizer(
            listOf(slow), writer, noCrops, scratch, testKnowledge(),
            recropTimeoutMs = RECROP_TIMEOUT_MS, clock = { spentMs },
        ).perform(set)

        assertTrue("прочитанные страницы пропали вместе с файлом", result is ActionResult.Success)
        assertTrue("заход вышел за потолок действия: $spentMs мс", spentMs < ACTION_CEILING_MS)
        val unread = (result as ActionResult.Success).result.metadata[META_TABLE_PAGES_UNREAD]?.toInt() ?: 0
        assertTrue("заход не остановился сам — читал все страницы подряд", unread > 0)
        val readPages = pages.size - unread
        assertTrue("не прочитано ни одной страницы", readPages > 0)
        val tail = lastRows!!.last().single()
        assertTrue(
            "страницы, до которых заход не дошёл, пропали из файла молча: $tail",
            tail.contains('⚠') && tail.contains("${readPages + 1}–${pages.size}"),
        )
        assertTrue(
            "человеку не сказано, почему заход остановился: $tail",
            "${TABLE_READ_BUDGET_MS / 60_000}" in tail,
        )
    }

    /**
     * #1243, решение владельца: «при пределе/обрыве — честная остановка с частичным файлом».
     *
     * Оценка по уже прочитанным верхней границы не даёт: страница у чужого бесплатного
     * провайдера упирается в отказ по лимиту и уходит по цепочке дольше, чем все прочитанные
     * вместе. Такая одна страница уносила с собой весь заход — потолок действия отменял его
     * целиком, — и человек оставался и без таблицы, и без квоты, потраченной на прочитанное.
     */
    @Test
    fun `зависшая страница не уносит с собой уже прочитанные (#1243)`() = runTest(timeout = 30.seconds) {
        pages = (1..4).map { page("IMG_$it.jpg") }
        val stuck = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                if (obj.metadata["name"] == "IMG_3.jpg") awaitCancellation()
                return answerOf("""[["Товар"],["Гречка"]]""")
            }
        }

        val result = ExcelRealizer(
            listOf(stuck), writer, noCrops, scratch, testKnowledge(),
            recropTimeoutMs = RECROP_TIMEOUT_MS, pagesBudgetMs = BUDGET_MS,
        ).perform(set)

        assertTrue("прочитанные страницы пропали вместе с зависшей", result is ActionResult.Success)
        val rows = lastRows!!
        assertTrue("прочитанного в файле нет: $rows", rows.any { it == listOf("Гречка") })
        val unread = (result as ActionResult.Success).result.metadata[META_TABLE_PAGES_UNREAD]!!.toInt()
        assertTrue("зависшая страница и хвост за ней не сосчитаны: $unread", unread >= 2)
        val readPages = pages.size - unread
        assertTrue("не прочитано ни одной страницы", readPages > 0)
        val tail = rows.last().single()
        assertTrue(
            "страницы, до которых заход не дошёл, пропали из файла молча: $tail",
            tail.contains('⚠') && tail.contains("${readPages + 1}–${pages.size}"),
        )
    }

    @Test
    fun `не дочиталась даже первая страница — отказ, а не пустой файл (#1243)`() = runTest(timeout = 30.seconds) {
        pages = listOf(page("IMG_1.jpg"), page("IMG_2.jpg"))
        val stuck = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject = awaitCancellation()
        }

        val result = ExcelRealizer(
            listOf(stuck), writer, noCrops, scratch, testKnowledge(),
            recropTimeoutMs = RECROP_TIMEOUT_MS, pagesBudgetMs = BUDGET_MS,
        ).perform(set)

        assertTrue("человек получил файл без единой прочитанной страницы", result is ActionResult.Failure)
        assertTrue("отказ не даёт повторить", (result as ActionResult.Failure).recoverable)
        assertNull("файл писался, хотя читать было нечего", lastPlan)
    }

    @Test
    fun `набор без страниц — ни снимка, ни PDF, ни текста — честный отказ`() = runTest {
        pages = listOf(
            PointObject("a", "application/zip", ScratchRef("/tmp/a.zip"), ObjectState(ObjectKind.ZIP), mapOf("name" to "a.zip")),
            PointObject("b", "audio/ogg", ScratchRef("/tmp/b.ogg"), ObjectState(ObjectKind.AUDIO), mapOf("name" to "b.ogg")),
        )

        val result = realizer("""[["A"]]""").perform(set)

        assertTrue(result is ActionResult.Failure)
        assertNull(lastPlan)
    }

    private companion object {

        const val READ_MS = 300L

        const val STUCK_MS = 5_000L

        /** Своё время захода по набору (#1243) — в тесте короткое: ждать девять минут некому. */
        const val BUDGET_MS = 500L
    }
}
