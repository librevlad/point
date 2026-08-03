package com.point.executors

import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.BlockAnswer
import com.point.core.flow.BlockContent
import com.point.core.flow.BlockRole
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CellAnswer
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
import com.point.core.flow.chromeWords
import com.point.core.flow.unreadWords
import com.point.core.flow.validateTable
import com.point.core.flow.withGrid
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

class ExcelRealizer(
    private val providers: List<@JvmSuppressWildcards LlmClient>,
    private val writer: SpreadsheetWriter,
    private val cropper: EvidenceCropper,
    private val store: ObjectStore,
    private val recropTimeoutMs: Long,
) : Realizer {

    /** Боевой срок перечита — общий [RECROP_TIMEOUT_MS]; свой параметр существует только для
     *  тестов, которым нельзя ждать полминуты настенного времени. */
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
                // #266: чтение — это ДОКУМЕНТ, а не только сетка. Голосуется по-прежнему сетка
                // (голосовать структуру нечем, и выдумывать голосование мы не будем), а шапка,
                // реквизиты, примечание и подписи живут в разобранной раскладке рядом.
                val layouts = mutableListOf<DocumentLayout>()
                val tables = mutableListOf<List<List<String>>>()
                // Спор модели с её же атомами (цифра!) — кандидаты уровня одной модели; копятся
                // отдельно и после голосования вливаются в общий дропдаун теми же ключами (row, col).
                val cellCandidates = mutableListOf<Map<Pair<Int, Int>, List<String>>>()
                val errors = mutableListOf<String>()
                var next = 0
                while (layouts.size < CONSENSUS_N && next < ordered.size) {
                    // Столько чтений, скольких не хватает до консенсуса, — и все ОДНОВРЕМЕННО.
                    // Второй заход случается там, где первый недодал ТАБЛИЦ (ответ пришёл, но
                    // читаемой таблицы в нём нет): цепочка «не вышло — берём следующего» жива,
                    // просто больше не оплачивается ожиданием в общем случае.
                    val reads = readTogether(ordered, next, CONSENSUS_N - layouts.size, input, prompt, next == 0)
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
                            // Ответ мимо адресного контракта (массив, TSV, прочий текст) — те же
                            // дословные ячейки в одном блоке-сетке: честность проверки не зависит от
                            // формата, которым модель решила ответить. Иначе диктовка целой таблицы,
                            // отвеченная TSV, миновала бы проверку страницы, которую тот же ответ в
                            // JSON бы не прошёл (ревью #258).
                            val answer = parseLayout(raw) ?: continue
                            val addressable = layer != null && index != null
                            val layout =
                                if (addressable) layer.resolveLayout(answer) else literalLayout(answer)
                            val gridRows = layout.grid?.rows.orEmpty()
                            // Таблица, где живого текста нет, а разорванные ячейки есть, — это не
                            // «пустой документ», это модель, перенумеровавшая метки: связь ответа со
                            // страницей порвана целиком. Отдать такой «успех» — вручить чистый бланк
                            // вместо прочитанной страницы (ревью #281); честный исход — отказ чтения.
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
                    // #346 (идея владельца): спорной ячейке — кроп у сильного маршрута, а не весь
                    // документ заново. Третий голос входит в голосование ячейки (agree), а не
                    // заменяет его; не успевшие в общий срок остаются спором и дропдауном, как
                    // раньше. Без кадра (PDF/текст), без слоя атомов или без сильной зрячей модели
                    // перечитывать нечем — свод отдаётся как есть.
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
                    // model-free logic check also marks cells one would silently guess (letter-in-number,
                    // broken id run) with ⚠ so the writer highlights them.
                    val suspect = validateTable(settled.rows)
                    val rows = settled.rows.mapIndexed { r, row ->
                        row.mapIndexed { c, v ->
                            val flagged = (r to c) in suspect || (r to c) in settled.candidates
                            if (flagged && !v.contains('⚠')) "$v⚠" else v
                        }
                    }
                    // Документ берёт **ведущее чтение** — самой зоркой модели, ответившей первой по
                    // порядку провайдеров. Голосовать структуру нечем: у блока нет ни ключа, ни
                    // порядка, по которым две раскладки узнали бы друг друга, и «свод раскладок»
                    // был бы выдуманным голосованием. Сетка при этом сведена честно и возвращается
                    // на своё место в документе.
                    val read = layouts.first()
                    // Происхождение ячейки принадлежит СВОЕЙ сетке: свод переставляет строки
                    // (alignRows), и перенос адресов на чужие координаты приписал бы значению
                    // происхождение, которого у него нет. Одно чтение — координаты те же.
                    val structural = if (tables.size == 1) read.grid?.structural.orEmpty() else emptySet()
                    val document = read.withGrid(GroundedTable(rows, settled.candidates, structural))
                    // Что в этом файле нельзя отдавать как прочитанное, решает режим чтения (#267):
                    // на рукописи цифры помечаются всегда. Режим приходит со входа — от энричера,
                    // который страницу и читал; не дошёл — судим по слою, который у нас на руках.
                    // Иначе рукопись уедет в файл неотмеченной просто потому, что фоновая волна не
                    // успела, — та же дыра, что #263 закрывал в «В Word+».
                    val mode = readingModeOf(input.metadata).takeIf { it != ReadingMode.UNKNOWN }
                        ?: readingModeOf(layer)
                    val plan = layoutSheet(document, mode)
                    // Файл без единой строки — не результат, а пустой бланк (ревью #266). Раньше
                    // это было невозможно: пустая сетка = отказ. С блоками появились два входа в
                    // пустоту — сетка, объявленная пустой, и страница, целиком названная «не
                    // документом», — и оба отдавали ПУСТОЙ .xlsx как успех, да ещё со словами
                    // «ничего не потеряно». Проверяем то, что реально доехало до файла, а не
                    // форму ответа: так закрыты обе двери сразу и любая третья.
                    if (plan.rows.isEmpty()) {
                        return@runCatching ActionResult.Failure(
                            "На странице не нашлось ничего, что можно положить в таблицу",
                            recoverable = true,
                        )
                    }
                    // disagreements carry the distinct readings as an in-cell dropdown (#200).
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
                                // Факты о результате пишет само действие: до тапа их не бывает и
                                // быть не должно — LLM никогда не на первом экране.
                                grid?.let {
                                    put(META_TABLE_GRID, "${it.rows.size}×${it.rows.maxOf { r -> r.size }}")
                                    put(
                                        META_TABLE_HEADER,
                                        headerLabel(minOf(document.gridHeaderRows, it.rows.size)),
                                    )
                                }
                                document.scope?.let { put(META_TABLE_SCOPE, scopeLabel(it)) }
                                // Отмычку «ссыпать половину документа в непрочитанное» ловит не
                                // порог, а публикация: число едет и в метаданные, и в файл строкой.
                                document.unreadWords.takeIf { it > 0 }
                                    ?.let { put(META_TABLE_UNREAD, it.toString()) }
                                // Вторая отмычка того же замка (ревью #266): слова, названные «не
                                // документом», в файл НЕ едут вовсе, поэтому строкой их не видно —
                                // значит число обязано публиковаться тем более. Без него «ничего не
                                // потеряно: да» стояло рядом с молча выброшенной половиной страницы.
                                document.chromeWords.takeIf { it > 0 }
                                    ?.let { put(META_TABLE_CHROME, it.toString()) }
                                // «Да» — и только когда правда; иначе ключа нет вовсе, а объясняет
                                // его отсутствие соседний ключ «непрочитанного».
                                if (coveredClaim(document, plan, mode) == true) put(META_TABLE_COVERED, "да")
                                put(META_TABLE_FLAGGED, flagged.toString())
                                // Режим чтения со входа не наследуется результатом сам (в scratch
                                // едут только метаданные результата), а без него рукописный путь
                                // не смог бы сказать, чем именно его гарантия слабее.
                                if (mode != ReadingMode.UNKNOWN) put(META_READING_MODE, mode.name)
                                put("models", layouts.size.toString())
                                // Сколько чтений реально стоит за таблицей. Меньше, чем читали, —
                                // значит они говорили о разных таблицах и свод не состоялся:
                                // человек держит в руках одно ничем не подтверждённое чтение, и
                                // узнать об этом он должен от нас, а не по расхождению с бумагой.
                                put("confirmedBy", consensus.sources.toString())
                            },
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

    /**
     * Один перечит (#346): строка спорной ячейки вырезается из исходного кадра **тем же
     * резаком**, что кроп-улика в Word (#267), и сильная зрячая модель отвечает, что в ячейке
     * написано. Кроп кладётся в scratch — живёт и чистится вместе с остальной копией документа.
     *
     * Резак тот же, а **размер — свой** ([CropPurpose.READING], #273): улику в документе смотрит
     * человек, и её ужимают до ширины колонки; здесь кусок читает модель, и ужатие тут — прямая
     * потеря знаков. Раньше назначения не было вовсе, и полоса строки — а она почти во всю ширину
     * кадра, у эталонной ведомости это 4000 px — уезжала зрячей модели ужатой до 1400. То есть
     * третий голос, заведённый ради взгляда на пиксели, смотрел на них через то самое ужатие,
     * цену которого замер уже назвал (#360).
     *
     * `null` — перечит не состоялся (кроп не вырезался, маршруты отказали): спор этой ячейки
     * просто остаётся человеку. Отказ одного маршрута — следующий сильный, как всюду в цепочке.
     */
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
                throw cancelled // отмена действия или общий срок перечита — не отказ маршрута
            } catch (_: Exception) {
                // отказ маршрута — пробуем следующего сильного; некому — спор остаётся
            }
        }
        return null
    }

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MAX_TEXT = 20_000

        /** Independent model reads to vote across (#200). 2 = уверенность за двойную ЦЕНУ, но уже
         *  не за двойное ВРЕМЯ: чтения идут одновременно ([readTogether]), поэтому ожидание — это
         *  самая медленная модель, а не сумма двух. Один провайдер вырождается в passthrough. */
        const val CONSENSUS_N = 2
        /**
         * Контракт структуры (#266) — единственное место, где чинится главный дефект: раньше
         * допустимая форма ответа была одна («массив строк из строк-ячеек»), слов «шапка», «итог»,
         * «примечание», «подпись» в запросе не было вовсе, и остальному документу **некуда было
         * лечь**. Терялось оно поэтому не при разборе, а при постановке вопроса.
         *
         * Словарь структуры в проекте уже был — строгие префиксы `T=/H=/B=/P=` у «В Word+»; сюда
         * он просто не был заведён.
         */
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
                "\"?\". " +
                // Раньше здесь стояло «не удаётся прочитать всю строку — пропусти её целиком», и
                // выбрасывались первыми строка-итог и строка-примечание: они читаются хуже
                // табличных. Пропущенное молча — худший из исходов; теперь ему есть куда лечь.
                "Ничего не выбрасывай: строку или надпись, которую разобрать не удалось, положи в " +
                "часть \"unread\", а не пропускай. " +
                "Без пояснений, без markdown, без ограждений ```."

        /**
         * Вопрос перечита (#346): кроп строки + «что написано в этой ячейке?». Варианты — только
         * контекст, не меню: модель просят прочитать пиксели, а не проголосовать вслепую, иначе
         * третий голос был бы эхом первых двух. «⚠» на нечитаемом — честное «не разобрал», оно
         * не голосует и не лезет в дропдаун.
         */
        const val RECROP_PROMPT =
            "На снимке — одна строка таблицы, вырезанная из фото документа. " +
                "В этой строке есть ячейка, которую прочитали по-разному; варианты чтения — в конце. " +
                "Найди эту ячейку и прочитай её заново по снимку. " +
                "Верни ТОЛЬКО содержимое ячейки, одной строкой, без пояснений, без markdown. " +
                "Не выбирай вариант вслепую: верни то, что видишь на снимке. " +
                "Если разобрать нельзя — верни ровно ⚠. " +
                "Варианты чтения: "

        /** Добавка к промпту при наличии индекса слов (#258): модель указывает, а не диктует. */
        const val ADDRESSED =
            "\n\nНиже — слова, уже прочитанные с этой страницы, построчно, каждое с меткой: [метка]слово. " +
                "Если слова ячейки есть в списке — верни ячейку НЕ текстом, а объектом {\"ids\":[\"w1\",\"w2\"]} " +
                "с метками её слов в точности как в списке. " +
                "Так же отвечают и части документа вне сетки: {\"role\":\"title\",\"ids\":[\"w3\",\"w4\"]}, " +
                "{\"role\":\"field\",\"label\":{\"ids\":[\"w10\"]},\"ids\":[\"w11\"]}. " +
                "Каждое слово списка должно попасть хоть куда-нибудь — в ячейку, в часть документа, " +
                "в \"chrome\" или в \"unread\". " +
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
    val cleaned = stripFence(raw)

    parseJsonTable(cleaned)?.let { return it }

    // Ответ начинается с «[», но строк в нём не нашлось — это JSON-ответ «таблицы нет» (модель
    // на примере 01 честно вернула `[]`), либо обломанный JSON. Ни то ни другое не TSV: пропущенный
    // в текстовый разбор, «[]» уезжал в Excel единственной ячейкой — мусор под видом успеха,
    // та же болезнь, что и одна скобка в ревью #258. Пустая таблица здесь — честный отказ:
    // действие скажет «не удалось распознать таблицу», а не отдаст файл со скобками.
    if (cleaned.startsWith("[")) return emptyList()

    return cleaned.lineSequence()
        .filter { it.isNotBlank() }
        .map { line -> line.split('\t').map { it.trim() } }
        .toList()
}

/**
 * Разбор ответа про **весь документ** (#266) — с совместимостью в обе стороны.
 *
 * Четыре формы входа и одна форма выхода:
 * - объект `{"scope":…,"blocks":[…]}` — контракт структуры целиком;
 * - **массив частей без обёртки** — тот же ответ, у которого модель уронила уровень;
 * - массив строк-ячеек — сегодняшний ответ: одна сетка и ничего вокруг;
 * - TSV — он же.
 *
 * Это чинит дефект, из-за которого **любой более богатый ответ был хуже плоского**: объект
 * отвергали оба разборщика (`startsWith("[")`), он уезжал в текстовый фолбэк, и фигурные скобки
 * ложились в ячейки как данные — под видом успеха. Ни один сегодняшний ответ при этом не
 * становится хуже: массив и TSV разбираются ровно как раньше, просто их результат теперь
 * называется блоком-сеткой.
 */
internal fun parseLayout(raw: String): LayoutAnswer? {
    val cleaned = stripFence(raw)
    if (cleaned.startsWith("{")) parseLayoutObject(cleaned)?.let { return it }
    parseLayoutArray(cleaned)?.let { return it }
    parseAddressedCells(cleaned)?.let { return tableOnly(it) }
    return parseTable(cleaned).takeIf { it.isNotEmpty() }
        ?.let { rows -> tableOnly(rows.map { row -> row.map { CellAnswer.Literal(it) } }) }
}

/**
 * Список частей документа **без обёртки** `{"blocks": …}` — та же уронённая ступенька, что уже
 * описана у [parseAddressedCells] («массив ячеек-объектов вместо массива строк»), только этажом
 * выше. Запрос просит объект, но словарь ролей модель усваивает раньше формы, а прошлый контракт
 * годами просил именно массив.
 *
 * Ловится это здесь, а не «как-нибудь»: без разбора такой ответ шёл в [parseAddressedCells], где
 * части документа становились ЯЧЕЙКАМИ ОДНОЙ СТРОКИ, часть-сетка — пустой ячейкой (у неё нет
 * `text`), и таблица исчезала целиком, а действие отчитывалось успехом. Ревью #266: ровно та
 * тихая потеря, ради которой срез и делался.
 *
 * Признак части — **названная роль или сетка**, а не «объект». Ячейка адресного ответа отвечает
 * `ids`/`text`, и принять её за часть значило бы сломать сегодняшний контракт #258.
 */
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

/**
 * Ответ без структуры — одна сетка и ничего вокруг.
 *
 * Заголовков у неё **одна строка**: таков сегодняшний контракт («первая строка — заголовки, если
 * они есть»), и менять его молчанием нельзя. Сказать «их нет» умеет только новая форма — там это
 * `"header": 0`, названное явно.
 */
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
    // Объект без списка частей, но с сеткой — тот же ответ, просто без обёртки: модель сэкономила
    // уровень. Уронить его в текстовый фолбэк значило бы положить JSON в ячейки как данные.
    blockAnswer(root)?.let { LayoutAnswer(listOf(it), scope) }
}.getOrNull()

/**
 * Одна часть документа. Роль неизвестна — часть становится примечанием: **содержимое важнее
 * места**, и потерять прочитанное из-за незнакомого слова было бы единственной настоящей ошибкой
 * (роль всё равно ничего не решает — она свидетельство, а не переключатель).
 */
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
    // Ячейку части собирает тот же разборщик, что ячейку сетки: у части нет собственного пути
    // чтения — ни своего «ids», ни своего «text».
    val cell = cellAnswer(obj)
    if (label == null && cell is CellAnswer.Literal && cell.text.isEmpty()) return null
    return BlockAnswer(role, label, BlockContent.Text(cell))
}

/** «сколько строк заняты заголовками»: число, «нет»/false — ноль, молчание — сегодняшняя единица. */
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

/**
 * Разбор адресного ответа (#258): JSON-таблица, где ячейка — строка либо объект
 * `{"ids":[...], "text"?}`. Понимает и старый ответ сплошными строками (все ячейки дословные),
 * поэтому модель, проигнорировавшая метки, не ломает путь. Не-JSON → null (→ TSV-фолбэк).
 */
internal fun parseAddressedCells(raw: String): List<List<CellAnswer>>? {
    val cleaned = stripFence(raw)
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
