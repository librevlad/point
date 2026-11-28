package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Сканированный PDF читается одним действием (#1014, решение владельца).
 *
 * «Найти в документе» было мертво ровно на тех документах, ради которых поиск делали:
 * у PDF без текстового слоя текст никто не читал, а путь «разложить на страницы → открыть
 * каждую → прочитать» существовал только руками. Это сцепка существующих способностей:
 * страницы рисует растеризатор, читает движок, знание ложится тем же ключом (#1157) — на сам
 * PDF. Качество (выпрямление, свет) — веха DOC-1, сюда не тянется.
 *
 * Одна способность на телефон и компьютер (#1379). Различаются только слова о цене:
 * [promise] — что человек получит и чем заплатит («· на телефоне» или «· страницы уйдут в
 * сервис»), [cost] и [network] — честные факты о том, кто на этой стороне читает. Их называет
 * каждая сторона сама, как у чтения снимка (#1021).
 */
class ReadDocumentCapability(
    private val promise: String = tr("текст всех страниц"),
    private val cost: Cost = Cost.FREE,
    private val network: Boolean = false,
) : Capability {
    override val id = ID

    override val icon = "ocr"

    // Отвечает на вопрос чтения (#1119): при уже прочитанном уходит вниз.
    override val meta = CapabilityMeta(
        priority = 24,
        cost = cost,
        latency = Latency.SLOW,
        network = network,
        answers = KnownCapabilities.IMAGE_TEXT,
    )

    override fun label(state: ObjectState) = tr("Прочитать документ")

    /**
     * Начатое и не доведённое до конца чтение зовётся тем, что оно сделает (карточка «Не
     * отвечать «Ничего не нашлось» по скану, прочитанному не целиком»): следующий заход читает
     * только непросмотренные страницы, а «Прочитать документ» человек принял бы за чтение
     * заново с первой.
     */
    override fun label(graph: GraphState): String =
        if (scan(graph.state) && readingToFinish(graph.facts, graph.state.has(Feature.HAS_TEXT))) {
            tr("Дочитать документ")
        } else {
            label(graph.state)
        }

    override fun accepts(state: ObjectState) = scan(state) && !state.has(Feature.HAS_TEXT)

    /**
     * Пока документ прочитан не целиком, дверь чтения остаётся — это следующий виток того же
     * Discovery, а не отдельное действие (карточка «Не отвечать «Ничего не нашлось» по скану,
     * прочитанному не целиком»).
     *
     * Прежде дверь закрывалась, едва у документа появлялся текст: скан на 110 страниц телефон
     * читал сотню, и оставшиеся десять не читались уже ничем; страницы, на которых сервис
     * отказал, — тоже, хотя слова самого чтения звали «попробуйте ещё раз».
     *
     * По голому состоянию ([accepts] со `ObjectState`) ответ прежний: счёт страниц — знание
     * документа, признаком он не выражается. Поэтому и объявление соседу о недочитанном не
     * говорит — дверь видна там, где виден граф: в списке своего устройства.
     */
    override fun accepts(graph: GraphState): Boolean =
        accepts(graph.state) || (scan(graph.state) && readingToFinish(graph.facts, graph.state.has(Feature.HAS_TEXT)))

    private fun scan(state: ObjectState) = state.kind == ObjectKind.PDF && state.has(Feature.IS_IMAGE_PDF)

    override fun produces(state: ObjectState) = state.with(Feature.HAS_TEXT)

    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    override fun yields(state: ObjectState) = ActionYield.Same(promise)

    // Имя работы — из общего словаря: одна работа на двух устройствах зовётся одинаково (#1254).
    companion object { val ID = KnownCapabilities.READ_DOCUMENT }
}

/**
 * Страницы читаются по одной, знание ложится на сам документ (#1014, #1157).
 *
 * Один исполнитель на обе стороны (#1379): цикл по страницам, счёт прочитанных и сорванных,
 * исход по [pagesRead] — всё это было написано дважды и совпадало дословно. Различаются только
 * органы: [rasterizer] рисует страницы (телефон — своим отрисовщиком PDF, компьютер — pdfbox),
 * [recognizer] читает страницу, [keeper] кладёт текст (телефон — в рабочую копию, компьютер —
 * рядом с документом, #995). [meta] — как исполнитель зовётся очереди.
 *
 * Чтение идёт заходами (карточка «Не отвечать «Ничего не нашлось» по скану, прочитанному не
 * целиком»): заход читает только страницы, не просмотренные прежде ([META_OCR_TEXT_COVERAGE]),
 * склеивает новый текст с прежним в порядке страниц и называет, что просмотрено теперь. Первый
 * заход — частный случай: прежде не просмотрено ничего.
 */
class ReadDocumentRealizer(
    private val rasterizer: PdfRasterizer,
    private val recognizer: TextRecognizer,
    private val keeper: TextKeeper,
    override val meta: RealizerMeta = RealizerMeta(),
) : Realizer {
    override val capabilityId = ReadDocumentCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(tr("Разбираю PDF на страницы"))
                val known = textRefForTravel(input.metadata)?.let { ref -> runCatching { File(ref).readText() }.getOrNull() }
                val before = readSoFar(input.metadata, known)
                val drawn = rasterizer.rasterize(input, before?.next ?: ALL_PAGES)

                // Номер страницы — по документу: первая нарисованная — `drawn.first`, а не
                // первая страница документа. Порядок файлов — порядок номеров в именах, и номер
                // длиннее — страница дальше: «page-1000» идёт после «page-999».
                val pages = File(drawn.dir.value).walkTopDown()
                    .filter { it.isFile }
                    .sortedWith(compareBy<File>({ it.name.length }, { it.name }))
                    .mapIndexed { index, file -> drawn.first + index to file }
                    .toList()
                if (pages.isEmpty()) {
                    return@withContext ActionResult.Failure(
                        readerFailure(READER_NO_PAGES, ObjectKind.PDF),
                        recoverable = true,
                    )
                }

                // Счёт страниц документа — от растеризатора, а не по папке: папка знает только
                // нарисованное, и срезанный хвост выглядел бы концом документа.
                val lastDrawn = pages.last().first
                val total = maxOf(drawn.total, lastDrawn)

                val texts = before?.texts.orEmpty().toMutableMap()
                val covered = before?.covered.orEmpty().toMutableSet()
                var newlyRead = 0
                var broken = 0
                var brokenSaid: String? = null
                pages.filter { (number, _) -> number !in covered }.forEach { (number, page) ->

                    // Номер и счёт — настоящие (карточка «Не отвечать «Ничего не нашлось» по
                    // скану, прочитанному не целиком»): у скана на 110 страниц первый заход
                    // говорил «из 100», хотя страниц в документе 110.
                    reportStage(tr("Читаю страницу {0} из {1}", number, total))
                    val text = runCatching {
                        recognizer.recognize(input.copy(mime = "image/png", uri = ScratchRef(page.absolutePath)))
                    }.getOrElse { trouble ->
                        broken++
                        brokenSaid = brokenSaid ?: (ownWordsOf(trouble) ?: readerFailure(trouble.message, ObjectKind.PDF))
                        null
                    } ?: return@forEach

                    // Читатель дошёл до ответа — страница просмотрена, с текстом она или пустая.
                    covered += number
                    newlyRead++
                    if (!noTextAnswer(text)) texts[number] = text.trim()
                }

                val coverage = PageCoverage(covered, total)
                val stoppedAt = lastDrawn.takeIf { (lastDrawn + 1..total).any { it !in covered } }
                val outcome = pagesRead(
                    total = total,
                    readable = texts.size,
                    broken = broken,
                    brokenSaid = brokenSaid,
                    covered = coverage.count,
                    newlyRead = newlyRead,
                    stoppedAt = stoppedAt,
                )
                val state = outcome.state
                    ?: return@withContext ActionResult.Failure(outcome.said, recoverable = true)
                val knowledge = mapOf(
                    investigationKey(KnownCapabilities.IMAGE_TEXT) to state.wire,
                    META_OCR_TEXT_COVERAGE to pageCoverageWire(coverage),
                )
                if (texts.isEmpty()) return@withContext ActionResult.Done(outcome.said, Findings(metadata = knowledge))

                val (read, starts) = joinedPages(texts)
                val ref = runCatching { keeper.keep(input, read) }.getOrNull()
                    ?: return@withContext ActionResult.Failure(TEXT_NOT_KEPT, recoverable = true)
                ActionResult.Done(
                    outcome.said,
                    Findings(
                        features = setOf(Feature.HAS_TEXT),
                        metadata = knowledge + mapOf(
                            META_OCR_TEXT_REF to ref,
                            META_OCR_TEXT_PAGES to pageStartsWire(starts),
                        ),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure(ownWordsOf(it) ?: readerFailure(it.message, ObjectKind.PDF), recoverable = true)
            }
        }

    /**
     * Текст документа из текстов страниц — в порядке страниц, а не заходов: дочитанная вторая
     * страница встаёт между первой и третьей, а не в конец.
     *
     * Где в склеенном тексте начинается каждая страница (#1014): без этого «Найти в документе»
     * знало, сколько мест нашлось, но не на каких страницах. Номер — по документу, поэтому пустая
     * или сорвавшаяся страница просто пропущена, а не сдвигает номера следующих.
     */
    private fun joinedPages(texts: Map<Int, String>): Pair<String, List<PageStart>> {
        val read = StringBuilder()
        val starts = mutableListOf<PageStart>()
        texts.toSortedMap().forEach { (page, text) ->
            if (read.isNotEmpty()) read.append(PAGE_BREAK)
            starts += PageStart(page = page, offset = read.length)
            read.append(text)
        }
        return read.toString() to starts
    }
}
