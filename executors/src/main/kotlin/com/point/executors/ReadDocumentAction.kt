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
import com.point.core.flow.InvestigationState
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
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

    companion object { val ID = CapabilityId("read-document") }
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
                        "В документе не нашлось ни одной страницы",
                        recoverable = true,
                    )
                }

                val read = StringBuilder()
                var readable = 0
                pages.forEachIndexed { index, page ->
                    reportStage("Читаю страницу ${index + 1} из ${pages.size}")
                    val text = runCatching {
                        recognizer.recognize(
                            input.copy(mime = "image/png", uri = com.point.core.model.ScratchRef(page.absolutePath)),
                        )
                    }.getOrDefault("")
                    if (text.isNotBlank()) {
                        readable++
                        if (read.isNotEmpty()) read.append("\n\n")
                        read.append(text.trim())
                    }
                }
                if (readable == 0) {
                    return@withContext ActionResult.Failure(
                        "Не разобрал текст ни на одной странице",
                        recoverable = true,
                    )
                }

                val ref = store.newScratchFile("txt")
                File(ref.value).writeText(read.toString())
                ActionResult.Done(
                    "Прочитано страниц: $readable из ${pages.size} — текст у документа",
                    Findings(
                        features = setOf(Feature.HAS_TEXT),
                        metadata = mapOf(
                            META_OCR_TEXT_REF to ref.value,
                            investigationKey(KnownCapabilities.IMAGE_TEXT) to readableOutcome(readable, pages.size),
                        ),
                    ),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось прочитать документ", recoverable = true) }
        }

    /** Прочитана часть страниц — вопрос не закрывается находкой целиком (ADR-0001 §9). */
    private fun readableOutcome(readable: Int, total: Int): String =
        if (readable == total) InvestigationState.FOUND.wire else InvestigationState.INSUFFICIENTLY_INVESTIGATED.wire
}
