package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.Realizer
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
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
                val pages = readPagesWithEyes(input, rasterizer, recognizer)
                if (pages.total == 0) {
                    return@withContext ActionResult.Failure(
                        "В документе не нашлось ни одной страницы",
                        recoverable = true,
                    )
                }
                if (pages.nothing) {
                    return@withContext ActionResult.Failure(
                        "Не разобрал текст ни на одной странице",
                        recoverable = true,
                    )
                }

                val ref = store.newScratchFile("txt")
                File(ref.value).writeText(pages.text)
                ActionResult.Done(pages.said(), pages.knowledge(ref))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось прочитать документ", recoverable = true) }
        }
}
