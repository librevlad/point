package com.point.executors

import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CellAnswer
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.Realizer
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.bareIndexId
import com.point.core.flow.normConsensus
import com.point.core.flow.promptIndex
import com.point.core.flow.reportStage
import com.point.core.flow.reconcile
import com.point.core.flow.resolveCells
import com.point.core.flow.styleCell
import com.point.core.flow.validateTable
import com.point.core.model.ActionResult
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

/**
 * image / pdf / text -> a spreadsheet. The LLM does the hard part (understanding a
 * real-world table); we ask for a **structured JSON** array-of-arrays — far more
 * reliable than parsing delimited text — and materialise a real .xlsx. Paid/network,
 * so it is a Pro action off the first screen (gated by the paywall seam).
 */
class ExcelCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "excel"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "В Excel"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.PDF, ObjectKind.TEXT)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    companion object { val ID = CapabilityId("excel") }
}

class ExcelRealizer @Inject constructor(
    private val providers: List<@JvmSuppressWildcards LlmClient>,
    private val writer: SpreadsheetWriter,
) : Realizer {
    override val capabilityId = ExcelCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // For image/pdf the file is inlined by the LLM client; for text we pass content in the prompt.
                val extra = if (input.state.kind == ObjectKind.TEXT) {
                    "\n\nТекст:\n" + File(input.uri.value).readText().take(MAX_TEXT)
                } else {
                    ""
                }
                // #258: на печатном документе, который телефон уже прочитал сам, модель указывает
                // на слова страницы вместо диктовки — текст ячейки собирается из атомов. Подменить
                // цифру незаметно нельзя: через метки её перепишет атом, а продиктованная мимо
                // страницы получит ⚠ (resolveCells). Слоя нет (рукопись, PDF, текст) — старый
                // контракт, дословно.
                reportStage(if (input.state.kind == ObjectKind.IMAGE) "Читаю страницу" else "Готовлю текст")
                val layer = atomLayer(input)
                val index = layer?.promptIndex()
                val prompt = if (index != null) PROMPT + ADDRESSED + index + extra else PROMPT + extra
                // #200: read the table with up to CONSENSUS_N independent strong-vision models, then
                // vote each cell (reconcile). A dense/handwritten table one model guesses, another catches
                // — agreement = confidence, disagreement = ⚠ + the models' distinct readings as candidates.
                val ordered = providers.sortedByDescending { it.strongVision }.filter { it.canHandle(input) }
                val tables = mutableListOf<List<List<String>>>()
                // Спор модели с её же атомами (цифра!) — кандидаты уровня одной модели; копятся
                // отдельно и после голосования вливаются в общий дропдаун теми же ключами (row, col).
                val cellCandidates = mutableListOf<Map<Pair<Int, Int>, List<String>>>()
                val errors = mutableListOf<String>()
                var next = 0
                while (tables.size < CONSENSUS_N && next < ordered.size) {
                    // Столько чтений, скольких не хватает до консенсуса, — и все ОДНОВРЕМЕННО.
                    // Второй заход случается там, где первый недодал ТАБЛИЦ (ответ пришёл, но
                    // читаемой таблицы в нём нет): цепочка «не вышло — берём следующего» жива,
                    // просто больше не оплачивается ожиданием в общем случае.
                    val reads = readTogether(ordered, next, CONSENSUS_N - tables.size, input, prompt, next == 0)
                    next = reads.next
                    // Порядок ответов — порядок провайдеров, а не порядок финиша: сетку консенсуса
                    // задаёт первая таблица (reconcile), и она обязана быть таблицей самой зоркой
                    // модели, кто бы ни ответил раньше.
                    for (read in reads.answers) {
                        val raw = read.getOrNull()
                        if (raw == null) {
                            val e = read.exceptionOrNull()!!
                            errors += e.message ?: e.javaClass.simpleName
                            continue
                        }
                        try {
                            val grounded = if (layer != null && index != null) {
                                // Ответ мимо адресного контракта (TSV, прочий текст) — те же дословные
                                // ячейки: честность проверки не зависит от формата, которым модель решила
                                // ответить. Иначе диктовка целой таблицы, отвеченная TSV, миновала бы
                                // проверку страницы, которую тот же ответ в JSON бы не прошёл (ревью #258).
                                val cells = parseAddressedCells(raw)
                                    ?: parseTable(raw).map { row -> row.map { CellAnswer.Literal(it) } }
                                        .takeIf { it.isNotEmpty() }
                                cells?.let(layer::resolveCells)
                            } else {
                                null
                            }
                            if (grounded != null) {
                                // Таблица, где живого текста нет, а разорванные ячейки есть, — это не
                                // «пустой документ», это модель, перенумеровавшая метки: связь ответа со
                                // страницей порвана целиком. Отдать такой «успех» — вручить чистый бланк
                                // вместо прочитанной страницы (ревью #281); честный исход — отказ чтения.
                                val cells = grounded.rows.flatten()
                                val torn = cells.all { it.isBlank() || it == "⚠" } && "⚠" in cells
                                if (torn) {
                                    errors += "Модель не смогла указать на слова страницы"
                                } else {
                                    grounded.rows.takeIf { it.isNotEmpty() }?.let {
                                        tables += it
                                        cellCandidates += grounded.candidates
                                    }
                                }
                            } else {
                                // Модель ответила не по адресному контракту (или слоя нет) — прежний путь.
                                parseTable(raw).takeIf { it.isNotEmpty() }?.let(tables::add)
                            }
                        } catch (e: Exception) {
                            errors += e.message ?: e.javaClass.simpleName
                        }
                    }
                }
                if (tables.isEmpty()) {
                    ActionResult.Failure(
                        errors.firstOrNull()?.substringBefore('\n')?.take(120) ?: "Не удалось распознать таблицу",
                        recoverable = true,
                    )
                } else {
                    reportStage(if (tables.size > 1) "Свожу расхождения чтений" else "Собираю таблицу")
                    val consensus = reconcile(tables) // 1 read → passthrough; ≥2 → voted, disagreements ⚠
                    // Кандидаты двух этажей под одним дропдауном: спор моделей между собой (reconcile)
                    // и спор модели с атомами страницы (#258). Согласие моделей второй спор не гасит:
                    // два пересказа, совпавшие друг с другом, — всё ещё не то, что напечатано.
                    // Спор с атомами едет к своей ячейке по якорю-содержимому (anchorCandidates), а в
                    // дропдауне каждое чтение живёт один раз — чистым, если хоть один этаж дал его чистым.
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
                    // model-free logic check also marks cells one would silently guess (letter-in-number,
                    // broken id run) with ⚠ so the writer highlights them.
                    val suspect = validateTable(consensus.rows)
                    val rows = consensus.rows.mapIndexed { r, row ->
                        row.mapIndexed { c, v ->
                            val flagged = (r to c) in suspect || (r to c) in candidates
                            if (flagged && !v.contains('⚠')) "$v⚠" else v
                        }
                    }
                    // disagreements carry the distinct readings as an in-cell dropdown (#200).
                    reportStage("Собираю файл")
                    val ref = writer.write(rows, candidates)
                    val flagged = rows.sumOf { row -> row.count { styleCell(it).flagged } }
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.OFFICE,
                            XLSX_MIME,
                            ref,
                            mapOf(
                                "op" to "excel", "name" to "таблица.xlsx",
                                "rows" to rows.size.toString(), "flagged" to flagged.toString(),
                                "models" to tables.size.toString(),
                                // Сколько чтений реально стоит за таблицей. Меньше, чем читали, —
                                // значит они говорили о разных таблицах и свод не состоялся:
                                // человек держит в руках одно ничем не подтверждённое чтение, и
                                // узнать об этом он должен от нас, а не по расхождению с бумагой.
                                "confirmedBy" to consensus.sources.toString(),
                            ),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка разбора в Excel", recoverable = true) }
        }

    /** Исходы одного захода чтения — **в порядке провайдеров** — и индекс первого, кого ещё
     *  не спрашивали. */
    private class Reads(val answers: List<Result<String>>, val next: Int)

    /**
     * Чтения одной страницы, идущие **одновременно** (#200).
     *
     * Консенсус двух моделей стоил ровно вдвое дороже по времени: цикл `for` отправлял кадр
     * второй модели только после ответа первой. На эталонной ведомости владельца (фото 4000×3000,
     * 35 строк) это ~2.5 минуты ожидания, из которых половина — стояние в очереди, а не работа.
     * Модели друг о друге ничего не знают, и на вход второй ответ первой не влияет — значит,
     * последовательность здесь была не инвариантом, а случайностью реализации.
     *
     * **Не «волна», а слоты.** Читающих ровно столько, сколько таблиц не хватает до консенсуса, и
     * освободившийся слот сразу берёт следующего кандидата, не дожидаясь соседа. Иначе первый же
     * провайдер, отказывающий мгновенно, съедал бы слот целиком: в живой цепочке первым стоит
     * «свой ключ» (`UserKeyLlmClient`), и без заданного ключа он падает за миллисекунду — фиксированная
     * пара «свой ключ + Gemini» оставила бы Claude на второй заход, то есть снова последовательно.
     *
     * Отказ соседа чтение не роняет: исход каждой модели заворачивается в [Result] **внутри**
     * корутины, поэтому исключение не отменяет `coroutineScope` и не уносит с собой того, кто
     * уже читает. Отмена всего действия человеком — наоборот, обязана проходить насквозь, поэтому
     * `CancellationException` пробрасывается, а не превращается в «ошибку модели».
     *
     * Ответы возвращаются в порядке провайдеров, а не в порядке финиша: сетку консенсуса задаёт
     * первая таблица, и голосование при равенстве отдаёт голос первому чтению
     * ([com.point.core.flow.agree]) — то есть от порядка зависит содержимое файла.
     */
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
        // Счётчик и рассказ о нём — под одним замком: две корутины, пришедшие одновременно,
        // иначе назвали бы экрану одно и то же число (#288 — стадия обязана быть правдой).
        val heard = Mutex()
        var done = 0
        (0 until slots).map {
            async {
                var retry = false
                while (true) {
                    val i = cursor.getAndIncrement()
                    if (i >= ordered.size) break
                    // Молчаливая замена отказавшей модели выглядит как зависшее чтение: слот
                    // начинает всё сначала, а секунды на экране продолжают идти от старого.
                    if (retry) heard.withLock { reportStage("Модель отказала — читаю следующей") }
                    val read = try {
                        Result.success(File(ordered[i].run(input, prompt).uri.value).readText())
                    } catch (cancelled: CancellationException) {
                        throw cancelled // отмена человеком — не отказ модели
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    collected[i] = read
                    if (read.isFailure) {
                        retry = true
                        continue // слот свободен — следующий кандидат, не дожидаясь соседа
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

    /**
     * Чем стадия честна теперь (#288). «Модель 1 из 2 читает таблицу» описывала очередь: пока
     * шло первое чтение, второго не существовало. Одновременным чтениям такая фраза врёт дважды —
     * и про то, что модель одна, и про то, что вторая ещё не начата. Говорим то, что есть:
     * сколько моделей смотрит на страницу прямо сейчас, а по мере ответов — сколько чтений готово.
     * Второй заход — это не «продолжаем», а «прошлые не дали таблицы», и звучать должен иначе.
     */
    private fun readingStage(n: Int, firstRound: Boolean): String = when {
        !firstRound && n > 1 -> "Перечитываю другими моделями"
        !firstRound -> "Перечитываю другой моделью"
        n > 1 -> "Таблицу читают $n ${modelsWord(n)} одновременно"
        else -> "Читаю таблицу"
    }

    /** «2 модели», «5 моделей» — стадия, собранная из числа и слова, не должна выглядеть
     *  машинным переводом. */
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

    /** Слой слов страницы, если распознавание его уже сложило; битый дамп не роняет действие —
     *  просто возвращает нас к старому контракту (и это видно по отсутствию меток в промпте). */
    private fun atomLayer(input: PointObject): AtomLayer? =
        input.metadata[META_OCR_ATOMS_REF]?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MAX_TEXT = 20_000

        /** Independent model reads to vote across (#200). 2 = уверенность за двойную ЦЕНУ, но уже
         *  не за двойное ВРЕМЯ: чтения идут одновременно ([readTogether]), поэтому ожидание — это
         *  самая медленная модель, а не сумма двух. Один провайдер вырождается в passthrough. */
        const val CONSENSUS_N = 2
        const val PROMPT =
            "Извлеки табличные данные из документа. Это может быть фото рукописной таблицы, " +
                "возможно под углом или повёрнутое — читай внимательно в любой ориентации. " +
                "Верни ТОЛЬКО JSON: массив строк, каждая строка — массив ячеек-строк, например " +
                "[[\"Дата\",\"Сумма\"],[\"16.07\",\"42\"]]. " +
                "Первая строка — заголовки, если они есть. " +
                "ВАЖНО: в каждой строке ровно столько столбцов, сколько их в источнике — не добавляй, " +
                "не повторяй и не дублируй столбцы. " +
                // Решение владельца по #345: в ячейке, где рука спорит с печатью, берём ОБА. Правило
                // стоит РАНЬШЕ прочих и сказано числом примеров: на ведомости модель в большинстве
                // ячеек отдавала оба числа, а в восьми молча оставила только рукописное — печатное
                // исчезало без следа, ни пометки, ни варианта. Решать за человека, какое из двух чисел
                // верное, продукт не должен; показать одно, потеряв другое, — это и есть решить.
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
                "\"?\"; если не удаётся прочитать всю строку — пропусти её целиком. " +
                "Без пояснений, без markdown, без ограждений ```."

        /** Добавка к промпту при наличии индекса слов (#258): модель указывает, а не диктует. */
        const val ADDRESSED =
            "\n\nНиже — слова, уже прочитанные с этой страницы, построчно, каждое с меткой: [метка]слово. " +
                "Если слова ячейки есть в списке — верни ячейку НЕ текстом, а объектом {\"ids\":[\"w1\",\"w2\"]} " +
                "с метками её слов в точности как в списке. " +
                "Если слово в списке прочитано с ошибкой (перепутана или потеряна буква) — добавь своё чтение: " +
                "{\"ids\":[\"w1\"],\"text\":\"исправленное\"}. " +
                "Ячейку-текст используй только когда её слов в списке нет совсем. " +
                "Не выдумывай метки: несуществующие будут отброшены. " +
                "Атрибут rule= у метки — подсказка офлайн-правила о форме слова " +
                "(например rule=track-shaped: похоже на номер отправления); подсказка может " +
                "ошибаться и ничего не решает — решаешь ты по контексту страницы.\n\nСлова страницы:\n"
    }
}

/**
 * Robust table parse: a structured JSON array-of-arrays first (what the model is now
 * asked for — reliable), falling back to TSV so a model that still answers in the old
 * delimited format keeps working. Tolerant of a stray code fence around either.
 */
internal fun parseTable(raw: String): List<List<String>> {
    val cleaned = raw.trim()
        .removePrefix("```json").removePrefix("```tsv").removePrefix("```")
        .removeSuffix("```")
        .trim()

    parseJsonTable(cleaned)?.let { return it }

    return cleaned.lineSequence()
        .filter { it.isNotBlank() }
        .map { line -> line.split('\t').map { it.trim() } }
        .toList()
}

/**
 * Разбор адресного ответа (#258): JSON-таблица, где ячейка — строка либо объект
 * `{"ids":[...], "text"?}`. Понимает и старый ответ сплошными строками (все ячейки дословные),
 * поэтому модель, проигнорировавшая метки, не ломает путь. Не-JSON → null (→ TSV-фолбэк).
 */
internal fun parseAddressedCells(raw: String): List<List<CellAnswer>>? {
    val cleaned = raw.trim()
        .removePrefix("```json").removePrefix("```tsv").removePrefix("```")
        .removeSuffix("```")
        .trim()
    if (!cleaned.startsWith("[")) return null
    return runCatching {
        val arr = JSONArray(cleaned)
        val elems = (0 until arr.length()).map { arr.opt(it) }
        when {
            elems.isEmpty() -> emptyList()
            // Модель «сплющила» уровень: массив ячеек-объектов вместо массива строк — типовой
            // сбой на таблице из одной строки. Ответ по контракту минус одна скобка — это одна
            // строка, а не мусор: раньше он падал в TSV-фолбэк и уезжал в xlsx сырым JSON под
            // видом успеха (ревью #258).
            elems.none { it is JSONArray } -> listOf(elems.map(::cellAnswer))
            // Ячейка, затесавшаяся между строками-массивами, — строка из одной ячейки: молча
            // выбросить её значило бы потерять указание на реально прочитанные слова страницы.
            else -> elems.map { e ->
                if (e is JSONArray) (0 until e.length()).map { j -> cellAnswer(e.opt(j)) }
                else listOf(cellAnswer(e))
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * Куда в консенсусной сетке приложить спор модели с её же атомами. Ключи [com.point.core.flow.GroundedTable.candidates]
 * живут в координатах таблицы **одной** модели, а [reconcile] выравнивает таблицы по индексу
 * строки: модель, пропустившая строку заголовка, сдвинута на строку, и её спор о цифре без
 * якорения приклеился бы к чужой ячейке — где выбор из дропдауна в один тап вписывает трек-номер
 * вместо заголовка, а настоящая спорная ячейка уходит в файл чистой (ревью #258).
 *
 * Якорь — атомное чтение спора (первый кандидат, по построению [resolveCells] это текст ячейки):
 * совпало с ячейкой на своём месте — туда; иначе — в единственную ячейку того же столбца с тем же
 * чтением. Неоднозначно — спор не прикладывается: сдвинутую путаницу в столбце reconcile уже
 * пометил своим ⚠, а дропдаун на чужой ячейке хуже отсутствия дропдауна.
 */
internal fun anchorCandidates(
    key: Pair<Int, Int>,
    candidates: List<String>,
    grid: List<List<String>>,
): Pair<Int, Int>? {
    val anchor = normConsensus(candidates.first())
    fun matches(r: Int, c: Int) = grid.getOrNull(r)?.getOrNull(c)?.let { normConsensus(it) == anchor } == true
    // Позиции не доверяем вовсе (ревью #294): после выравнивания строк по содержимому номер
    // строки в таблице модели больше не равен номеру в консенсусной сетке, и «своё место»
    // может оказаться чужой строкой с тем же чтением. Привязываем только когда чтение в
    // столбце единственно; иначе дропдауна не будет — это лучше, чем дропдаун не на своей
    // ячейке, где выбор в один тап портит значение.
    return grid.indices.filter { matches(it, key.second) }.singleOrNull()?.let { it to key.second }
}

private fun cellAnswer(cell: Any?): CellAnswer = when {
    cell is JSONObject -> {
        val ids = idList(cell.opt("ids"))
        // isNull обязателен: платформенный org.json на устройстве превращает явный
        // {"text": null} в строку "null" через optString — JVM-тесты на эталонной библиотеке
        // этого не видят, а на телефоне рождался ложный спор с атомами (ревью #281).
        val text = if (cell.isNull("text")) null else cell.optString("text").takeIf { it.isNotEmpty() }
        // Объект без единой метки — это дословная ячейка, как бы модель её ни завернула.
        if (ids.isEmpty()) CellAnswer.Literal(text ?: "") else CellAnswer.Ids(ids, text)
    }
    // Голый массив на месте ячейки — модель сэкономила на обёртке {"ids": …}; принять дешевле,
    // чем уронить её указание в текст вида ["w1","w2"] посреди таблицы.
    cell is JSONArray -> CellAnswer.Ids(idList(cell))
    cell == null || cell == JSONObject.NULL -> CellAnswer.Literal("")
    else -> CellAnswer.Literal(cell.toString())
}

/** Метки из чего угодно, чем модель их завернула: массив, одиночная строка или число.
 *  Выбросить `{"ids":"a1"}` молча значило бы потерять указание на реально прочитанное
 *  слово страницы (ревью #281); null-элементы внутри массива — не метки. */
private fun idList(ids: Any?): List<String> = when {
    ids is JSONArray -> (0 until ids.length()).mapNotNull { i ->
        ids.opt(i)?.takeIf { it != JSONObject.NULL }?.toString()?.let(::bareId)
    }
    ids == null || ids == JSONObject.NULL -> emptyList()
    else -> listOf(bareId(ids.toString()))
}

/** Метка без атрибутов индекса — общий срез синтаксиса промпта живёт в ядре ([bareIndexId]):
 *  кандидаты «Понять» (#261) цитируют те же метки с теми же атрибутами. */
private fun bareId(id: String): String = bareIndexId(id)

/** A JSON array-of-arrays → rows, or null if it is not that shape (→ TSV fallback). */
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
