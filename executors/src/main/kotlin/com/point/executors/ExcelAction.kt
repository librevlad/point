package com.point.executors

import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.BlockAnswer
import com.point.core.flow.BlockContent
import com.point.core.flow.BlockRole
import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CellAnswer
import com.point.core.flow.KEY_SETTINGS_CALL
import com.point.core.flow.Consensus
import com.point.core.flow.Cost
import com.point.core.flow.CropEvidence
import com.point.core.flow.CropPurpose
import com.point.core.flow.DocScope
import com.point.core.flow.DocumentLayout
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.GroundedTable
import com.point.core.flow.Latency
import com.point.core.flow.LayoutAnswer
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.META_TABLE_CHROME
import com.point.core.flow.META_TABLE_COVERED
import com.point.core.flow.META_TABLE_FLAGGED
import com.point.core.flow.META_TABLE_GRID
import com.point.core.flow.META_TABLE_HEADER
import com.point.core.flow.META_TABLE_SCOPE
import com.point.core.flow.META_TABLE_UNREAD
import com.point.core.flow.ObjectStore
import com.point.core.flow.refusalNeedsKey
import com.point.core.flow.RECROP_TIMEOUT_MS
import com.point.core.flow.ReadingMode
import com.point.core.flow.Realizer
import com.point.core.flow.RecropQuestion
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.coveredClaim
import com.point.core.flow.recropDisputed
import com.point.core.flow.bareIndexId
import com.point.core.flow.grid
import com.point.core.flow.gridHeaderRows
import com.point.core.flow.headerLabel
import com.point.core.flow.layoutSheet
import com.point.core.flow.literalLayout
import com.point.core.flow.normConsensus
import com.point.core.flow.promptIndex
import com.point.core.flow.readingModeOf
import com.point.core.flow.reportStage
import com.point.core.flow.reconcile
import com.point.core.flow.resolveLayout
import com.point.core.flow.scopeLabel
import com.point.core.flow.styleCell
import com.point.core.flow.survivedHeaderRows
import com.point.core.flow.chromeWords
import com.point.core.flow.unfitTable
import com.point.core.flow.unreadWords
import com.point.core.flow.validateTable
import com.point.core.flow.withGrid
import com.point.core.flow.labelNeedingKey
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class ExcelCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "excel"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = labelNeedingKey("В Excel", keys.keySet())
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.PDF, ObjectKind.TEXT)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.OFFICE, "таблицу")

    companion object { val ID = CapabilityId("excel") }
}

class ExcelRealizer(
    private val providers: List<@JvmSuppressWildcards LlmClient>,
    private val writer: SpreadsheetWriter,
    private val cropper: EvidenceCropper,
    private val store: ObjectStore,
    private val recropTimeoutMs: Long,
) : Realizer {

    @Inject constructor(
        providers: List<@JvmSuppressWildcards LlmClient>,
        writer: SpreadsheetWriter,
        cropper: EvidenceCropper,
        store: ObjectStore,
    ) : this(providers, writer, cropper, store, RECROP_TIMEOUT_MS)

    override val capabilityId = ExcelCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

                val extra = if (input.state.kind == ObjectKind.TEXT) {
                    "\n\nТекст:\n" + File(input.uri.value).readText().take(MAX_TEXT)
                } else {
                    ""
                }

                reportStage(if (input.state.kind == ObjectKind.IMAGE) "Читаю страницу" else "Готовлю текст")
                val layer = atomLayer(input)
                val index = layer?.promptIndex()
                val prompt = if (index != null) PROMPT + ADDRESSED + index + extra else PROMPT + extra

                val ordered = providers
                    .filter { it.configured && it.canHandle(input) }
                    .sortedByDescending { it.strongVision }

                val layouts = mutableListOf<DocumentLayout>()
                val tables = mutableListOf<List<List<String>>>()

                val cellCandidates = mutableListOf<Map<Pair<Int, Int>, List<String>>>()
                val errors = mutableListOf<String>()
                var next = 0
                while (layouts.size < CONSENSUS_N && next < ordered.size) {

                    val reads = readTogether(ordered, next, CONSENSUS_N - layouts.size, input, prompt, next == 0)
                    next = reads.next

                    for (read in reads.answers) {
                        val raw = read.getOrNull()
                        if (raw == null) {
                            val e = read.exceptionOrNull()!!
                            errors += e.message ?: e.javaClass.simpleName
                            continue
                        }
                        try {

                            val answer = parseLayout(raw) ?: continue
                            val addressable = layer != null && index != null
                            val layout =
                                if (addressable) layer.resolveLayout(answer) else literalLayout(answer)
                            val gridRows = layout.grid?.rows.orEmpty()

                            val cells = gridRows.flatten()
                            if (addressable && cells.all { it.isBlank() || it == "⚠" } && "⚠" in cells) {
                                errors += "Модель не смогла указать на слова страницы"
                                continue
                            }
                            layouts += layout
                            if (gridRows.isNotEmpty()) {
                                tables += gridRows
                                cellCandidates += layout.grid?.candidates.orEmpty()
                            }
                        } catch (e: Exception) {
                            errors += e.message ?: e.javaClass.simpleName
                        }
                    }
                }
                if (layouts.isEmpty()) {
                    ActionResult.Failure(refusalOf(errors, ordered.isEmpty()), recoverable = true)
                } else {
                    reportStage(if (tables.size > 1) "Свожу расхождения чтений" else "Собираю таблицу")
                    val consensus = reconcile(tables)

                    val candidates = (
                        consensus.candidates.asSequence().map { it.key to it.value } +
                            cellCandidates.asSequence().flatMap { perModel ->
                                perModel.asSequence().mapNotNull { (key, cand) ->
                                    anchorCandidates(key, cand, consensus.rows)?.let { it to cand }
                                }
                            }
                        )
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, lists) ->
                            lists.flatten().distinct()
                                .groupBy(::normConsensus).values
                                .map { g -> g.firstOrNull { !it.contains('⚠') } ?: g.first() }
                        }

                    val eyes = ordered.filter { it.strongVision }
                    val settled = if (input.state.kind == ObjectKind.IMAGE && layer != null && eyes.isNotEmpty()) {
                        recropDisputed(
                            Consensus(consensus.rows, candidates, consensus.sources),
                            layer,
                            recropTimeoutMs,
                        ) { question -> reread(input, layer, question, eyes) }
                    } else {
                        Consensus(consensus.rows, candidates, consensus.sources)
                    }

                    val suspect = validateTable(settled.rows)
                    val rows = settled.rows.mapIndexed { r, row ->
                        row.mapIndexed { c, v ->
                            val flagged = (r to c) in suspect || (r to c) in settled.candidates
                            if (flagged && !v.contains('⚠')) "$v⚠" else v
                        }
                    }

                    val read = layouts.first()

                    val structural = if (tables.size == 1) read.grid?.structural.orEmpty() else emptySet()

                    val headerRows = survivedHeaderRows(
                        read.grid?.rows.orEmpty(),
                        rows,
                        read.gridHeaderRows,
                    )
                    val document = read.withGrid(
                        GroundedTable(rows, settled.candidates, structural),
                        headerRows,
                    )

                    val gridCells = rows.sumOf { row -> row.count { it.isNotBlank() } }
                    val disputed = (settled.candidates.keys + suspect).count { (r, c) ->
                        rows.getOrNull(r)?.getOrNull(c)?.isNotBlank() == true
                    }
                    unfitTable(document, disputed, gridCells)?.let {
                        return@runCatching ActionResult.Failure(it, recoverable = true)
                    }

                    val mode = readingModeOf(input.metadata).takeIf { it != ReadingMode.UNKNOWN }
                        ?: readingModeOf(layer)
                    val plan = layoutSheet(document, mode)

                    if (plan.rows.isEmpty()) {
                        return@runCatching ActionResult.Failure(
                            "На странице не нашлось ничего, что можно положить в таблицу",
                            recoverable = true,
                        )
                    }

                    reportStage("Собираю файл")
                    val ref = writer.write(plan)
                    val flagged = plan.rows.sumOf { row -> row.count { styleCell(it).flagged } }
                    val grid = document.grid?.takeIf { it.rows.isNotEmpty() }
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.OFFICE,
                            XLSX_MIME,
                            ref,
                            buildMap {
                                put("op", "excel")
                                put("name", "таблица.xlsx")

                                grid?.let {
                                    put(META_TABLE_GRID, "${it.rows.size}×${it.rows.maxOf { r -> r.size }}")
                                    put(
                                        META_TABLE_HEADER,
                                        headerLabel(minOf(document.gridHeaderRows, it.rows.size)),
                                    )
                                }
                                document.scope?.let { put(META_TABLE_SCOPE, scopeLabel(it)) }

                                document.unreadWords.takeIf { it > 0 }
                                    ?.let { put(META_TABLE_UNREAD, it.toString()) }

                                document.chromeWords.takeIf { it > 0 }
                                    ?.let { put(META_TABLE_CHROME, it.toString()) }

                                if (coveredClaim(document, plan, mode) == true) put(META_TABLE_COVERED, "да")
                                put(META_TABLE_FLAGGED, flagged.toString())

                                if (mode != ReadingMode.UNKNOWN) put(META_READING_MODE, mode.name)
                                put("models", layouts.size.toString())

                                put("confirmedBy", consensus.sources.toString())
                            },
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка разбора в Excel", recoverable = true) }
        }

    private class Reads(val answers: List<Result<String>>, val next: Int)

    private suspend fun readTogether(
        ordered: List<LlmClient>,
        from: Int,
        need: Int,
        input: PointObject,
        prompt: String,
        firstRound: Boolean,
    ): Reads = coroutineScope {
        val slots = minOf(need, ordered.size - from)
        reportStage(readingStage(slots, firstRound))
        val cursor = AtomicInteger(from)
        val collected = ConcurrentHashMap<Int, Result<String>>()

        val heard = Mutex()
        var done = 0
        (0 until slots).map {
            async {
                var retry = false
                while (true) {
                    val i = cursor.getAndIncrement()
                    if (i >= ordered.size) break

                    if (retry) heard.withLock { reportStage("Модель отказала — читаю следующей") }
                    val read = try {
                        Result.success(File(ordered[i].run(input, prompt).uri.value).readText())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    collected[i] = read
                    if (read.isFailure) {
                        retry = true
                        continue
                    }
                    heard.withLock {
                        done++
                        if (done < slots) reportStage("Готово $done из $slots чтений — жду остальные")
                    }
                    break
                }
            }
        }.awaitAll()
        Reads(collected.toSortedMap().values.toList(), minOf(cursor.get(), ordered.size))
    }

    private fun readingStage(n: Int, firstRound: Boolean): String = when {
        !firstRound && n > 1 -> "Перечитываю другими моделями"
        !firstRound -> "Перечитываю другой моделью"
        n > 1 -> "Таблицу читают $n ${modelsWord(n)} одновременно"
        else -> "Читаю таблицу"
    }

    private fun modelsWord(n: Int): String {
        val tens = n % 100
        val ones = n % 10
        return when {
            tens in 11..14 -> "моделей"
            ones == 1 -> "модель"
            ones in 2..4 -> "модели"
            else -> "моделей"
        }
    }

    private fun atomLayer(input: PointObject): AtomLayer? =
        input.metadata[META_OCR_ATOMS_REF]?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }

    private suspend fun reread(
        input: PointObject,
        layer: AtomLayer,
        question: RecropQuestion,
        eyes: List<LlmClient>,
    ): String? {
        val cut = cropper.crop(
            CropEvidence(
                imagePath = input.uri.value,
                region = question.region,
                uprightDegrees = layer.transform?.rotationDegrees ?: 0,
                purpose = CropPurpose.READING,
            ),
        ) ?: return null
        val ref = store.newScratchFile(cut.extension)
        File(ref.value).writeBytes(cut.bytes)
        val crop = PointObject(
            id = "recrop-${question.cell.first}-${question.cell.second}",
            mime = if (cut.extension == "jpg") "image/jpeg" else "image/${cut.extension}",
            uri = ref,
            state = ObjectState(ObjectKind.IMAGE),
        )
        val prompt = RECROP_PROMPT +
            question.readings.joinToString(" / ") { "«${it.replace("⚠", "").trim()}»" }
        for (eye in eyes) {
            if (!eye.canHandle(crop)) continue
            try {
                return File(eye.run(crop, prompt).uri.value).readText()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {

            }
        }
        return null
    }

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MAX_TEXT = 20_000

        const val CONSENSUS_N = 2

        const val STRUCTURE =
            "Верни ТОЛЬКО JSON-объект: {\"scope\":…,\"blocks\":[…]}, без пояснений. " +
                "blocks — части документа сверху вниз, у каждой есть \"role\": " +
                "\"title\" — заголовок документа; " +
                "\"field\" — реквизит (у него ещё \"label\" с названием: \"Клиент\", \"Дата\"); " +
                "\"table\" — сетка; \"totals\" — итоги; \"note\" — примечание или сноска; " +
                "\"sign\" — подписи; " +
                "\"chrome\" — то, что видно, но документом НЕ является: строка состояния телефона, " +
                "панель приложения, полосы прокрутки, соседнее окно, чужая карточка; " +
                "\"unread\" — слова видно, а отнести их не к чему. " +
                "Часть-сетка несёт \"rows\": [[ячейка,…],…] и \"header\" — сколько СТРОК сетки заняты " +
                "заголовками: 0 (заголовков нет вовсе), 1 (одна строка) или 2 (двухуровневая шапка). " +

                "Заголовок колонки, напечатанный в несколько строк («Військове\\nзвання»), — ОДНА " +
                "ячейка: верни его одной строкой, а не двумя ячейками. В строке заголовков ровно " +
                "столько ячеек, сколько в строках данных: не дроби заголовок и не склеивай в одну " +
                "ячейку заголовки соседних колонок. " +
                "Остальные части несут \"text\" со своим текстом. " +
                "\"scope\": \"full\" — документ попал в кадр целиком, \"viewport\" — виден только экран, " +
                "за краем есть ещё, \"cropped\" — часть документа обрезана краем кадра. " +
                "Пример: {\"scope\":\"full\",\"blocks\":[" +
                "{\"role\":\"title\",\"text\":\"Рахунок №7\"}," +
                "{\"role\":\"field\",\"label\":\"Клієнт\",\"text\":\"Термінал\"}," +
                "{\"role\":\"table\",\"header\":1,\"rows\":[[\"Товар\",\"Кіль-ть\"],[\"Гречка\",\"2\"]]}," +
                "{\"role\":\"note\",\"text\":\"Відпуск без довіреності заборонено\"}]}. "

        const val PROMPT =
            "Прочитай документ ЦЕЛИКОМ и верни всё, что на нём видно, — не только табличную сетку, " +
                "но и заголовок, реквизиты, итоги, примечания и подписи. Это может быть фото " +
                "рукописной таблицы, возможно под углом или повёрнутое — читай внимательно в любой " +
                "ориентации. " +
                STRUCTURE +
                "ВАЖНО: в каждой строке сетки ровно столько столбцов, сколько их в источнике — не " +
                "добавляй, не повторяй и не дублируй столбцы. " +

                "ВАЖНО: в ячейке бывает ДВА значения — напечатанное и дописанное от руки рядом с ним " +
                "(не зачёркнутое). Верни ОБА через пробел, в том порядке, в каком они стоят в ячейке: " +
                "\"0,72 0,883\", \"2,04 1,994\", \"1,0 0,882\", \"3,0 2,990\". Дописанное от руки НЕ " +
                "заменяет напечатанное и не отменяет его: вернуть только рукописное, отбросив " +
                "печатное (или наоборот), — ошибка. " +
                "Зачёркнутое/исправленное помечай так: \"~~53~~ 40\" (было 53, стало 40) или \"~~52~~\" " +
                "(просто зачёркнуто). " +
                "Если ячейку видно, но ты НЕ уверен в прочтении — добавь символ ⚠ в конец её текста " +
                "(например \"Гречка⚠\"): её подсветят для проверки. " +
                "Не выдумывай данные: если ячейку не разобрать совсем — оставь её пустой (\"\"), НЕ ставь " +
                "\"?\". " +

                "Ничего не выбрасывай: строку или надпись, которую разобрать не удалось, положи в " +
                "часть \"unread\", а не пропускай. " +
                "Без пояснений, без markdown, без ограждений ```."

        const val RECROP_PROMPT =
            "На снимке — одна строка таблицы, вырезанная из фото документа. " +
                "В этой строке есть ячейка, которую прочитали по-разному; варианты чтения — в конце. " +
                "Найди эту ячейку и прочитай её заново по снимку. " +
                "Верни ТОЛЬКО содержимое ячейки, одной строкой, без пояснений, без markdown. " +
                "Не выбирай вариант вслепую: верни то, что видишь на снимке. " +
                "Если разобрать нельзя — верни ровно ⚠. " +
                "Варианты чтения: "

        const val ADDRESSED =
            "\n\nНиже — слова, уже прочитанные с этой страницы, построчно, каждое с меткой: [метка]слово. " +
                "Список прочитан телефоном и содержит ошибки: перепутанные буквы, лишние скобки, " +
                "подчёркивания и запятые по краям слов. Не повторяй их — читай снимок сам. " +
                "Если слова ячейки есть в списке — верни ячейку НЕ текстом, а объектом " +
                "{\"ids\":[\"w1\",\"w2\"],\"text\":\"как ты читаешь эту ячейку\"}: метки говорят, ГДЕ на " +
                "странице стоит ячейка, \"text\" — ЧТО в ней написано. Оба поля обязательны, и " +
                "\"text\" ставь ВСЕГДА, а не только когда заметил ошибку. " +
                "Так же отвечают и части документа вне сетки: " +
                "{\"role\":\"title\",\"ids\":[\"w3\",\"w4\"],\"text\":\"Заголовок\"}, " +
                "{\"role\":\"field\",\"label\":{\"ids\":[\"w10\"]},\"ids\":[\"w11\"],\"text\":\"значение\"}. " +
                "Строки списка — это полосы страницы, а не строки таблицы: заголовок или ячейка, " +
                "напечатанные в две строки, стоят в списке на разных строках, но остаются ОДНОЙ " +
                "ячейкой — соберите её метки вместе. " +
                "Каждое слово списка должно попасть хоть куда-нибудь — в ячейку, в часть документа, " +
                "в \"chrome\" или в \"unread\". " +
                "Ячейку-текст без меток используй только когда её слов в списке нет совсем. " +
                "Не выдумывай метки: несуществующие будут отброшены. " +
                "Атрибут rule= у метки — подсказка офлайн-правила о форме слова " +
                "(например rule=track-shaped: похоже на номер отправления); подсказка может " +
                "ошибаться и ничего не решает — решаешь ты по контексту страницы.\n\nСлова страницы:\n"
    }
}

internal fun refusalOf(errors: List<String>, noReaders: Boolean): String {
    if (noReaders) return "Читать таблицу некем — $KEY_SETTINGS_CALL"
    val substantive = errors.firstOrNull { !refusalNeedsKey(it) }
    val chosen = substantive ?: errors.firstOrNull() ?: return "Не удалось распознать таблицу"

    // «Software caused connection abort» уходил исходом на экран (живая находка
    // владельца, 2026-08-09): транспортная ошибка — не слова для человека.
    if (com.point.core.flow.looksLikeNetworkFailure(chosen)) {
        return "Связь оборвалась, таблица не дочиталась — проверьте интернет и попробуйте ещё раз"
    }
    if (com.point.core.flow.looksLikeQuotaFailure(chosen)) {
        return "Бесплатные лимиты чтения исчерпаны — вернитесь позже, платить не идём"
    }
    return chosen.substringBefore('\n').take(120)
}

internal fun parseTable(raw: String): List<List<String>> {
    val cleaned = stripFence(raw)

    parseJsonTable(cleaned)?.let { return it }

    if (cleaned.startsWith("[") || cleaned.startsWith("{")) return emptyList()

    return cleaned.lineSequence()
        .filter { it.isNotBlank() }
        .map { line -> line.split('\t').map { it.trim() } }
        .toList()
}

internal fun parseLayout(raw: String): LayoutAnswer? {
    val cleaned = stripFence(raw)
    if (cleaned.startsWith("{")) parseLayoutObject(cleaned)?.let { return it }
    parseLayoutArray(cleaned)?.let { return it }
    parseAddressedCells(cleaned)?.let { return tableOnly(it) }
    return parseTable(cleaned).takeIf { it.isNotEmpty() }
        ?.let { rows -> tableOnly(rows.map { row -> row.map { CellAnswer.Literal(it) } }) }
}

private fun parseLayoutArray(s: String): LayoutAnswer? {
    if (!s.startsWith("[")) return null
    return runCatching {
        val arr = JSONArray(s)
        val elems = (0 until arr.length()).map { arr.opt(it) }
        val blocks = elems.filterIsInstance<JSONObject>()
        if (elems.isEmpty() || blocks.size != elems.size) return@runCatching null
        if (blocks.none { it.has("role") || it.has("rows") }) return@runCatching null
        elems.mapNotNull(::blockAnswer).takeIf { it.isNotEmpty() }?.let { LayoutAnswer(it) }
    }.getOrNull()
}

private fun tableOnly(cells: List<List<CellAnswer>>) = LayoutAnswer(
    listOf(BlockAnswer(BlockRole.TABLE, null, BlockContent.Grid(cells, DEFAULT_HEADER_ROWS))),
)

private fun parseLayoutObject(s: String): LayoutAnswer? = runCatching {
    val root = JSONObject(s)
    val scope = docScope(root.optString("scope"))
    val blocks = root.optJSONArray("blocks")
    if (blocks != null) {
        val parsed = (0 until blocks.length()).mapNotNull { blockAnswer(blocks.opt(it)) }
        return@runCatching parsed.takeIf { it.isNotEmpty() }?.let { LayoutAnswer(it, scope) }
    }

    blockAnswer(root)?.let { LayoutAnswer(listOf(it), scope) }
}.getOrNull()

private fun blockAnswer(raw: Any?): BlockAnswer? {
    val obj = raw as? JSONObject ?: return null
    val role = blockRole(obj.optString("role"))
    val label = obj.opt("label")?.takeIf { it != JSONObject.NULL }?.let(::cellAnswer)
    val rows = obj.optJSONArray("rows")
    if (rows != null) {
        val cells = (0 until rows.length()).map { i ->
            when (val row = rows.opt(i)) {
                is JSONArray -> (0 until row.length()).map { j -> cellAnswer(row.opt(j)) }
                else -> listOf(cellAnswer(row))
            }
        }
        return BlockAnswer(role, label, BlockContent.Grid(cells, headerRowsOf(obj.opt("header"))))
    }

    val cell = cellAnswer(obj)
    if (label == null && cell is CellAnswer.Literal && cell.text.isEmpty()) return null
    return BlockAnswer(role, label, BlockContent.Text(cell))
}

private fun headerRowsOf(raw: Any?): Int = when (raw) {
    null, JSONObject.NULL -> DEFAULT_HEADER_ROWS
    is Boolean -> if (raw) 1 else 0
    is Number -> raw.toInt().coerceAtLeast(0)
    else -> raw.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_HEADER_ROWS
}

private fun blockRole(raw: String?): BlockRole = when (raw?.trim()?.lowercase()) {
    "title", "heading" -> BlockRole.TITLE
    "field", "fields", "requisite" -> BlockRole.FIELD
    "table", "grid" -> BlockRole.TABLE
    "totals", "total", "summary" -> BlockRole.TOTALS
    "sign", "signature", "signatures" -> BlockRole.SIGN
    "chrome", "ui" -> BlockRole.CHROME
    "unread", "unknown", "unreadable" -> BlockRole.UNREAD
    else -> BlockRole.NOTE
}

private fun docScope(raw: String?): DocScope? = when (raw?.trim()?.lowercase()) {
    "full", "whole" -> DocScope.FULL
    "viewport", "screen" -> DocScope.VIEWPORT
    "cropped", "crop", "partial" -> DocScope.CROPPED
    else -> null
}

private const val DEFAULT_HEADER_ROWS = 1

private fun stripFence(raw: String): String = raw.trim()
    .removePrefix("```json").removePrefix("```tsv").removePrefix("```")
    .removeSuffix("```")
    .trim()

internal fun parseAddressedCells(raw: String): List<List<CellAnswer>>? {
    val cleaned = stripFence(raw)
    if (!cleaned.startsWith("[")) return null
    return runCatching {
        val arr = JSONArray(cleaned)
        val elems = (0 until arr.length()).map { arr.opt(it) }
        when {
            elems.isEmpty() -> emptyList()

            elems.none { it is JSONArray } -> listOf(elems.map(::cellAnswer))

            else -> elems.map { e ->
                if (e is JSONArray) (0 until e.length()).map { j -> cellAnswer(e.opt(j)) }
                else listOf(cellAnswer(e))
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

internal fun anchorCandidates(
    key: Pair<Int, Int>,
    candidates: List<String>,
    grid: List<List<String>>,
): Pair<Int, Int>? {
    val anchor = normConsensus(candidates.first())
    fun matches(r: Int, c: Int) = grid.getOrNull(r)?.getOrNull(c)?.let { normConsensus(it) == anchor } == true

    return grid.indices.filter { matches(it, key.second) }.singleOrNull()?.let { it to key.second }
}

private fun cellAnswer(cell: Any?): CellAnswer = when {
    cell is JSONObject -> {
        val ids = idList(cell.opt("ids"))

        val text = if (cell.isNull("text")) null else cell.optString("text").takeIf { it.isNotEmpty() }

        if (ids.isEmpty()) CellAnswer.Literal(text ?: "") else CellAnswer.Ids(ids, text)
    }

    cell is JSONArray -> CellAnswer.Ids(idList(cell))
    cell == null || cell == JSONObject.NULL -> CellAnswer.Literal("")
    else -> CellAnswer.Literal(cell.toString())
}

private fun idList(ids: Any?): List<String> = when {
    ids is JSONArray -> (0 until ids.length()).mapNotNull { i ->
        ids.opt(i)?.takeIf { it != JSONObject.NULL }?.toString()?.let(::bareId)
    }
    ids == null || ids == JSONObject.NULL -> emptyList()
    else -> listOf(bareId(ids.toString()))
}

private fun bareId(id: String): String = bareIndexId(id)

private fun parseJsonTable(s: String): List<List<String>>? {
    if (!s.startsWith("[")) return null
    return runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            (arr.opt(i) as? JSONArray)?.let { row ->
                (0 until row.length()).map { j -> row.get(j).toString() }
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
