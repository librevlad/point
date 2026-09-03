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
    private val promise: String = "текст всех страниц",
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

    override fun label(state: ObjectState) = "Прочитать документ"

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF && state.has(Feature.IS_IMAGE_PDF) && !state.has(Feature.HAS_TEXT)

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
                reportStage("Разбираю PDF на страницы")
                val dir = rasterizer.rasterize(input)
                val pages = File(dir.value).walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
                if (pages.isEmpty()) {
                    return@withContext ActionResult.Failure(
                        readerFailure(READER_NO_PAGES, ObjectKind.PDF),
                        recoverable = true,
                    )
                }

                val read = StringBuilder()
                var readable = 0
                var broken = 0
                var brokenSaid: String? = null
                pages.forEachIndexed { index, page ->
                    reportStage("Читаю страницу ${index + 1} из ${pages.size}")
                    val text = runCatching {
                        recognizer.recognize(input.copy(mime = "image/png", uri = ScratchRef(page.absolutePath)))
                    }.getOrElse { trouble ->
                        broken++
                        brokenSaid = brokenSaid ?: (ownWordsOf(trouble) ?: readerFailure(trouble.message, ObjectKind.PDF))
                        null
                    }
                    if (text != null && !noTextAnswer(text)) {
                        readable++
                        if (read.isNotEmpty()) read.append("\n\n")
                        read.append(text.trim())
                    }
                }

                val outcome = pagesRead(pages.size, readable, broken, brokenSaid)
                val state = outcome.state
                    ?: return@withContext ActionResult.Failure(outcome.said, recoverable = true)
                if (readable == 0) {
                    return@withContext ActionResult.Done(
                        outcome.said,
                        Findings(metadata = mapOf(investigationKey(KnownCapabilities.IMAGE_TEXT) to state.wire)),
                    )
                }

                val ref = runCatching { keeper.keep(input, read.toString()) }.getOrNull()
                    ?: return@withContext ActionResult.Failure(TEXT_NOT_KEPT, recoverable = true)
                ActionResult.Done(
                    outcome.said,
                    Findings(
                        features = setOf(Feature.HAS_TEXT),
                        metadata = mapOf(
                            META_OCR_TEXT_REF to ref,
                            investigationKey(KnownCapabilities.IMAGE_TEXT) to state.wire,
                        ),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure(ownWordsOf(it) ?: readerFailure(it.message, ObjectKind.PDF), recoverable = true)
            }
        }
}
