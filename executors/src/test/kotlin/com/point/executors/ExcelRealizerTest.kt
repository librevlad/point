package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.CollectionContent
import com.point.core.flow.CropEvidence
import com.point.core.flow.CropPurpose
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.SheetPlan
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.UNREAD_CAPTION
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

/** The realizer parses the LLM's TSV into rows and hands them to the writer. */
class ExcelRealizerTest {

    private var lastPrompt: String? = null

    // The real LlmClient writes its answer to scratch and returns a ResultObject to it.
    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastPrompt = prompt
            return answerOf(answer)
        }
    }

    /** Ответ модели так, как его отдаёт настоящий клиент — файлом в scratch. */
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

    /**
     * Строки сетки — без хвоста «непрочитанного» (#266).
     *
     * Слова страницы, на которые ответ не указал ни одной меткой, теперь едут в файл отдельной
     * частью под своей подписью, и стоит она после таблицы. Проверки про сетку смотрят на сетку;
     * что до непокрытого доезжает, проверяет [ExcelLayoutTest].
     */
    private fun gridRows(): List<List<String>> =
        lastRows!!.takeWhile { it.firstOrNull() != UNREAD_CAPTION }

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    /** Резак, который ничего не вырезал: перечит спорных ячеек (#346) тихо не состоится. */
    private val noCrops = object : EvidenceCropper {
        override suspend fun crop(evidence: CropEvidence): EvidenceImage? = null
    }

    /** Scratch для кропа перечита — настоящий временный файл, потому что его читает LLM-фейк. */
    private val scratch = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String): PointObject = error("unused")
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("unused")
        override suspend fun put(result: ResultObject): PointObject = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) = ScratchRef(
            File.createTempFile("point-crop", ".$extension").apply { deleteOnExit() }.absolutePath,
        )
        override suspend fun clear() = Unit
    }

    /** A realizer over one or more canned model reads (consensus votes across them). */
    private fun realizer(vararg answers: String) =
        ExcelRealizer(answers.map { llm(it) }, writer, noCrops, scratch)

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
        // Индекс слов приложен к запросу, и куски трека несут улику правила (#258, design v3 §4).
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

    // -- находки ревью #281: щели адресного контракта --

    /** Метка строкой вместо массива — небрежность модели, а не повод молча выбросить указание. */
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

    /** Скобка индекса показывает `[a1 rule=track-shaped]`, и модель может процитировать её
     *  целиком — собственный синтаксис срезается, указание не теряется (ревью #283). */
    @Test
    fun `метка, процитированная вместе с атрибутом rule, не теряется`() = runTest {
        realizer("""[[{"ids":["a1 rule=track-shaped","a2 rule=track-shaped","a3 rule=track-shaped"]}]]""")
            .perform(imageWithAtoms())

        assertEquals(listOf(listOf("20 4514 9154 9395")), gridRows())
    }

    /** Явный null в поле text не должен рождать спор: на устройстве платформенный optString
     *  вернул бы строку "null" — от этого защищает isNull-гвард (ревью #281). */
    @Test
    fun `явный null в тексте ячейки — не чтение модели`() = runTest {
        realizer("""[[{"ids":["a1"],"text":null}]]""").perform(imageWithAtoms())

        assertEquals(listOf(listOf("20")), gridRows())
        assertTrue(lastCandidates.isEmpty())
    }

    /** Таблица, где модель перенумеровала все метки: связь со страницей порвана целиком —
     *  это отказ чтения, а не «успешно распознанный» чистый бланк (ревью #281). */
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

    /** Продиктованная строкой цифра, которой нет на странице, — помечена как диктовка. */
    @Test
    fun `диктовка цифры мимо страницы видна в xlsx`() = runTest {
        realizer("""[[{"ids":["h1"]},"1600"]]""").perform(imageWithAtoms())

        assertEquals(listOf(listOf("Трек-номер", "1600⚠")), gridRows())
    }

    // -- находки ревью #258, второй заход: дыры честности между этажами --

    /** Согласие моделей не гасит спор со страницей: два пересказа, совпавшие друг с другом,
     *  — всё ещё не то, что напечатано. Раньше голосование срезало ⚠ при группировке. */
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

    /** «Указала в никуда» одной модели — отсутствие, а не чтение: слово, прочитанное со страницы
     *  второй, побеждает, и «⚠» не становится вариантом в дропдауне. */
    @Test
    fun `разорванная ячейка не побеждает голосование и не лезет в дропдаун`() = runTest {
        realizer("""[[{"ids":["zz"]},{"ids":["h1"]}]]""", """[[{"ids":["a1"]},{"ids":["h1"]}]]""")
            .perform(imageWithAtoms())

        assertEquals(listOf(listOf("20", "Трек-номер")), gridRows())
        assertTrue(lastCandidates.isEmpty())
    }

    /** Модель «сплющила» уровень вложенности — это таблица из одной строки,
     *  а не сырой JSON в ячейках xlsx под видом успеха. */
    @Test
    fun `сплющенный адресный ответ читается как строка ячеек, а не как текст`() = runTest {
        realizer("""[{"ids":["h1"]},{"ids":["a1","a2","a3"]}]""").perform(imageWithAtoms())

        assertEquals(listOf(listOf("Трек-номер", "20 4514 9154 9395")), lastRows)
    }

    /** Честность не зависит от формата ответа: TSV при живом слое проходит ту же проверку
     *  страницы, что и JSON, — иначе диктовка легализуется выбором сериализации. */
    @Test
    fun `TSV-ответ при живом слое не минует проверку диктовки`() = runTest {
        realizer("Итого\t1600").perform(imageWithAtoms())

        assertEquals(listOf(listOf("Итого", "1600⚠")), lastRows)
    }

    // -- два чтения идут одновременно (консенсус #200 перестал стоить двойного ожидания) --

    /**
     * Каждое чтение отвечает только после того, как СОСЕД начал своё. Последовательный цикл на
     * этом встаёт: первая модель ждёт того, кто ещё не запущен, отваливается по таймауту — и до
     * консенсуса доживает одна таблица. Две таблицы в ответе = чтения шли параллельно.
     */
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
            writer, noCrops, scratch,
        ).perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("2", (result as ActionResult.Success).result.metadata["models"])
    }

    /** Упавшая модель забирает с собой только себя — соседнее чтение доезжает до файла. */
    @Test
    fun `отказ одной модели не роняет чтение соседней`() = runTest {
        val broken = object : LlmClient {
            override val strongVision = true
            override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("HTTP 429 quota")
        }

        val result = ExcelRealizer(listOf(broken, llm("""[["A","B"]]""")), writer, noCrops, scratch)
            .perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("A", "B")), lastRows)
    }

    /**
     * Пришедший раньше не становится первым чтением. Голос при равенстве отдаётся первому
     * (`agree`), а сетку строк задаёт первая таблица (`reconcile`) — то есть от порядка зависит
     * содержимое файла, и порядок обязан остаться порядком моделей, а не порядком финиша.
     */
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

        ExcelRealizer(listOf(strongButSlow, quick), writer, noCrops, scratch).perform(image)

        assertEquals("42⚠", lastRows!![0][1]) // победило чтение сильной модели, спор помечен
        assertEquals(listOf("42", "43"), lastCandidates[0 to 1])
    }

    /**
     * Мгновенный отказ не съедает слот до конца чтения: освободившийся слот сразу берёт следующего
     * кандидата. Это и есть живой случай — первым в цепочке стоит «свой ключ», и без ключа он
     * падает за миллисекунду; фиксированная пара оставила бы вторую сильную модель на потом.
     */
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
                withTimeout(STUCK_MS) { bothRead.await() } // ответим, только когда читают обе
                return answerOf(answer)
            }
        }
        var result: ActionResult? = null

        val heard = stagesHeard {
            result = ExcelRealizer(
                listOf(keyless, waiting("""[["№","42"]]"""), waiting("""[["№","42"]]""")),
                writer, noCrops, scratch,
            ).perform(image)
        }

        assertTrue(result is ActionResult.Success)
        assertEquals("2", (result as ActionResult.Success).result.metadata["models"])
        assertTrue(heard.contains("Модель отказала — читаю следующей"))
    }

    /**
     * Модели ответили, но читаемой таблицы не дали — цепочка «не вышло, берём следующего» жива:
     * читают оставшиеся. И это событие называется своим именем, а не продолжением первого.
     */
    @Test
    fun `когда чтения не дали таблиц, читают следующие модели`() = runTest {
        var result: ActionResult? = null

        val heard = stagesHeard {
            result = ExcelRealizer(
                listOf(llm("   "), llm("   "), llm("""[["A","B"]]""")),
                writer, noCrops, scratch,
            ).perform(image)
        }

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("A", "B")), lastRows)
        assertTrue(heard.contains("Перечитываю другой моделью"))
    }

    /** «Модель 1 из 2 читает таблицу» описывала очередь, которой больше нет (#288). */
    @Test
    fun `стадии не выдают одновременное чтение за очередь`() = runTest {
        val heard = stagesHeard {
            realizer("""[["№","42"]]""", """[["№","42"]]""").perform(image)
        }

        assertTrue(heard.contains("Таблицу читают 2 модели одновременно"))
        assertTrue(heard.contains("Готово 1 из 2 чтений — жду остальные"))
        assertTrue(heard.none { it.startsWith("Модель ") })
    }

    /** Одна модель — одна фраза без чисел, а не «1 из 1». */
    @Test
    fun `с единственной моделью стадия не считает несуществующих соседей`() = runTest {
        val heard = stagesHeard { realizer("""[["A","B"]]""").perform(image) }

        assertTrue(heard.contains("Читаю таблицу"))
        assertTrue(heard.none { it.contains("из 1") })
    }

    /**
     * Замер на моках, ради которого правка и делалась. Два чтения по [READ_MS]: по очереди
     * ожидание — их сумма, одновременно — самое медленное из них. Меряется именно фаза чтения
     * (по отметкам внутри моков), а не весь `perform` — на разборе и записи файла время тратится
     * одинаково в обоих мирах, и включать его в замер значило бы разбавлять то, что изменилось.
     * Утверждается отношение, а не абсолютное число, — порог в миллисекундах падал бы от загрузки
     * машины, а не от регрессии.
     */
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
            ExcelRealizer(listOf(slow("""[["№","42"]]"""), slow("""[["№","42"]]""")), writer, noCrops, scratch)

        val result = realizer.perform(image)

        assertTrue(result is ActionResult.Success)
        val queued = spans.sumOf { it.second - it.first } // столько ждали бы по очереди
        val together = spans.maxOf { it.second } - spans.minOf { it.first } // столько ждали на деле
        println("два чтения по $READ_MS мс — по очереди $queued мс, одновременно $together мс")
        assertTrue("ждали $together мс против $queued мс по очереди", together * 4 < queued * 3)
    }

    /** Модель пропустила заголовок — её таблица сдвинута на строку: спор о цифре едет к своей
     *  ячейке по якорю-содержимому, а не к чужой по сырым координатам. */
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

    /**
     * Решение владельца по #345: в ячейке, где рука спорит с печатью, берём ОБА.
     *
     * На ведомости модель в большинстве ячеек отдавала оба числа, а в одной молча оставила
     * только рукописное: напечатанное «0,230» исчезло без следа. Контракт назван явно,
     * потому что модель его не угадывает — и проверяется на самом промпте, а не на надежде.
     */
    @Test
    fun `промпт требует оба значения, когда рука дописана поверх печати (#345)`() = runTest {
        val r = realizer("[[\"Арт.\",\"До видачі\"],[\"11025\",\"1,0 0,230\"]]")

        r.perform(image)

        val prompt = lastPrompt!!
        assertTrue("правило «оба значения» названо", prompt.contains("Верни ОБА"))
        assertTrue("замена запрещена прямо", prompt.contains("заменяет напечатанное"))
        // Оба числа доезжают до файла целиком — ничто по дороге не выбрасывает одно из них.
        assertEquals("1,0 0,230", lastRows!![1][1])
    }

    // -- #346: спорная ячейка перечитывается кропом у сильного маршрута --

    /** Кадр ведомости со слоем атомов: два ряда «артикул название количество». */
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

    /** Резак, запоминающий адрес: тест сверяет, что кроп режется из исходного кадра. */
    private class RecordingCropper : EvidenceCropper {
        var seen: CropEvidence? = null
        override suspend fun crop(evidence: CropEvidence): EvidenceImage? {
            seen = evidence
            return EvidenceImage(byteArrayOf(7), 8, 8)
        }
    }

    /** Сильная зрячая модель: страницу читает таблицей, перечит кропа — содержимым ячейки. */
    private fun eyes(table: String, cell: String) = object : LlmClient {
        override val strongVision = true
        override suspend fun run(obj: PointObject, prompt: String): ResultObject =
            answerOf(if ("Варианты чтения" in prompt) cell else table)
    }

    /** Чтения расходятся в количестве гречки: «0,120» против «0,125». */
    private val tableA = """[["11004","Гречка","0,120"],["11006","Рис","0,500"]]"""
    private val tableB = """[["11004","Гречка","0,125"],["11006","Рис","0,500"]]"""

    @Test
    fun `согласный перечит кропом снимает спор — ячейка чистая, дропдауна нет (#346)`() = runTest {
        val cropper = RecordingCropper()
        var result: ActionResult? = null

        val heard = stagesHeard {
            result = ExcelRealizer(
                listOf(eyes(tableA, "0,120"), eyes(tableB, "0,120")), writer, cropper, scratch,
            ).perform(sheetImage())
        }

        assertTrue(result is ActionResult.Success)
        assertEquals("0,120", lastRows!![0][2]) // большинство 2 из 3 — спор ушёл вместе с пометкой
        assertTrue(lastCandidates.isEmpty())
        assertEquals("кроп режется из исходного кадра", "/tmp/sheet.png", cropper.seen!!.imagePath)
        // Кусок здесь читает модель, а не разглядывает человек: ужимать его до ширины колонки
        // документа значит отдать третьему голосу те же пиксели, но мельче (#273).
        assertEquals(CropPurpose.READING, cropper.seen!!.purpose)
        assertTrue(heard.contains("Переспрашиваю 1 спорную ячейку"))
    }

    @Test
    fun `несогласный перечит спор не гасит — третье чтение встаёт в дропдаун (#346)`() = runTest {
        val result = ExcelRealizer(
            listOf(eyes(tableA, "0,999"), eyes(tableB, "0,999")), writer, RecordingCropper(), scratch,
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
                if ("Варианты чтения" in prompt) awaitCancellation() // перечит молчит до отмены
                return answerOf(table)
            }
        }

        val result = ExcelRealizer(
            listOf(sleepy(tableA), sleepy(tableB)), writer, RecordingCropper(), scratch,
            recropTimeoutMs = 200,
        ).perform(sheetImage())

        assertTrue(result is ActionResult.Success)
        assertEquals("0,120⚠", lastRows!![0][2])
        assertEquals(listOf("0,120", "0,125⚠"), lastCandidates[0 to 2])
    }

    @Test
    fun `спорной ячейке без места на кадре кроп не режется — спор остаётся человеку (#346)`() = runTest {
        // Итоговой строки нет в слое атомов (движок её не собрал) — адреса на кадре у спора нет.
        val cropper = RecordingCropper()
        val a = """[["11004","Гречка","0,120"],["11006","Рис","0,500"],["Разом","до видачі","2400"]]"""
        val b = """[["11004","Гречка","0,120"],["11006","Рис","0,500"],["Разом","до видачі","2100"]]"""

        val result = ExcelRealizer(
            listOf(eyes(a, "2400"), eyes(b, "2400")), writer, cropper, scratch,
        ).perform(sheetImage())

        assertTrue(result is ActionResult.Success)
        assertNull("приложить соседнюю строку хуже, чем ничего", cropper.seen)
        assertEquals(listOf("2400⚠", "2100⚠"), lastCandidates[2 to 2])
    }

    /**
     * Живой прогон примера 01 (03.08.2026): модель честно ответила «таблицы нет» пустым
     * массивом `[]` — а в Excel уехала таблица из одной ячейки с текстом «[]»: пустой JSON
     * проваливался в текстовый разбор. Мусор под видом успеха хуже честного отказа.
     */
    @Test
    fun `пустой JSON-ответ — честный отказ, а не ячейка со скобками`() = runTest {
        assertTrue(parseTable("[]").isEmpty())
        assertTrue(parseTable("[ ]").isEmpty())
        assertTrue("обломанный JSON — тоже не TSV", parseTable("[[\"А\",").isEmpty())

        val result = realizer("[]").perform(image)

        assertTrue("пустой ответ — отказ действия", result is ActionResult.Failure)
    }

    private companion object {
        /** Столько «читает» модель в замере — заметно на часах и незаметно для прогона тестов. */
        const val READ_MS = 300L

        /** Столько ждёт чтение, встав в очередь вместо параллели: тест обязан упасть, а не висеть. */
        const val STUCK_MS = 5_000L
    }
}
