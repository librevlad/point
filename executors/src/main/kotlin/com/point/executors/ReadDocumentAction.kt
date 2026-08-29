package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.Latency
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.Realizer
import com.point.core.flow.TextRecognizer
import com.point.core.flow.investigationKey
import com.point.core.flow.noTextAnswer
import com.point.core.flow.ownWordsOf
import com.point.core.flow.pagesRead
import com.point.core.flow.READER_NO_PAGES
import com.point.core.flow.readerFailure
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Сканированный PDF читается одним действием (#1014, решение владельца).
 *
 * «Найти в документе» было мертво ровно на тех документах, ради которых поиск делали:
 * у PDF без текстового слоя текст никто не читал, а путь «разложить на страницы → открыть
 * каждую → прочитать» существовал только руками. Это сцепка существующих способностей:
 * страницы рисует тот же растеризатор, читает тот же движок, знание ложится тем же ключом
 * (#1157) — на сам PDF. Качество (выпрямление, свет) — веха DOC-1, сюда не тянется.
 */
class ReadDocumentCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ocr"

    // Отвечает на вопрос чтения (#1119): при уже прочитанном уходит вниз.
    override val meta = CapabilityMeta(
        priority = 24,
        cost = Cost.FREE,
        latency = Latency.SLOW,
        answers = KnownCapabilities.IMAGE_TEXT,
    )

    override fun label(state: ObjectState) = "Прочитать документ"

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF && state.has(Feature.IS_IMAGE_PDF) && !state.has(Feature.HAS_TEXT)

    override fun produces(state: ObjectState) = state.with(Feature.HAS_TEXT)

    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    override fun yields(state: ObjectState) = ActionYield.Same("текст всех страниц · на телефоне")

    // Имя работы — из общего словаря: одна работа на двух устройствах зовётся одинаково (#1254).
    companion object { val ID = KnownCapabilities.READ_DOCUMENT }
}

class ReadDocumentRealizer @Inject constructor(
    private val rasterizer: PdfRasterizer,
    private val recognizer: TextRecognizer,
    private val store: ObjectStore,
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
                        recognizer.recognize(
                            input.copy(mime = "image/png", uri = com.point.core.model.ScratchRef(page.absolutePath)),
                        )
                    }.getOrElse { trouble ->

                        // Сорвавшаяся страница — не пустая (#1254). Прежде «getOrDefault("")»
                        // превращал не заведшийся движок в страницу без текста, и документ, на
                        // котором не прочиталось ничего, получал утверждение о себе — «не
                        // разобрал текст» — вместо правды о том, что чтения не было.
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

                // Правило «сколько прочитано → в каком состоянии вопрос» — общее с компьютером (#1254).
                val outcome = pagesRead(pages.size, readable, broken, brokenSaid)
                val state = outcome.state
                    ?: return@withContext ActionResult.Failure(outcome.said, recoverable = true)
                if (readable == 0) {
                    return@withContext ActionResult.Done(
                        outcome.said,
                        Findings(
                            metadata = mapOf(
                                investigationKey(KnownCapabilities.IMAGE_TEXT) to state.wire,
                            ),
                        ),
                    )
                }

                val ref = store.newScratchFile("txt")
                File(ref.value).writeText(read.toString())
                ActionResult.Done(
                    outcome.said,
                    Findings(
                        features = setOf(Feature.HAS_TEXT),
                        metadata = mapOf(
                            META_OCR_TEXT_REF to ref.value,
                            investigationKey(KnownCapabilities.IMAGE_TEXT) to state.wire,
                        ),
                    ),
                )

                // Чужой текст библиотеки на экран не выходит (#686/#1254): на битом PDF здесь
                // стояло `it.message` — английский хвост от разбора документа.
            }.getOrElse {
                ActionResult.Failure(
                    ownWordsOf(it) ?: readerFailure(it.message, ObjectKind.PDF),
                    recoverable = true,
                )
            }
        }
}
