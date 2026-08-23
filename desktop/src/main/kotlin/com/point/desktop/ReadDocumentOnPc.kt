package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.Latency
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.Realizer
import com.point.core.flow.investigationKey
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

/**
 * Сканированный PDF читается и на компьютере (#1014, решение владельца: сцепка на обоих).
 *
 * На ПК от страницы-картинки к тексту не было ни одного пути, хотя облачное чтение у него
 * есть, а страницы умеет рисовать pdfbox. Это та же сцепка, что на телефоне: страницы →
 * существующее чтение → знание на сам PDF тем же ключом. Локального движка у ПК нет —
 * читает облако, поэтому действие честно сетевое.
 */
class PcReadDocumentCapability : Capability {
    override val id = ID
    override val icon = "ocr"

    override val meta = CapabilityMeta(
        priority = 24,
        cost = Cost.PAID,
        latency = Latency.SLOW,
        network = true,
        answers = KnownCapabilities.IMAGE_TEXT,
    )

    override fun label(state: ObjectState) = "Прочитать документ"

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF && state.has(Feature.IS_IMAGE_PDF) && !state.has(Feature.HAS_TEXT)

    override fun produces(state: ObjectState) = state.with(Feature.HAS_TEXT)

    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    override fun yields(state: ObjectState) = ActionYield.Same("текст всех страниц · страницы уйдут в сервис")

    companion object { val ID = CapabilityId("read-document") }
}

class PcReadDocumentRealizer(

    /** Чтение одной страницы — существующий облачный путь, за швом для тестов без сети. */
    private val readPage: suspend (File) -> String,
) : Realizer {
    override val capabilityId = PcReadDocumentCapability.ID

    override val meta = com.point.core.flow.RealizerMeta(kind = com.point.core.flow.RealizerKind.CLOUD)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(input.uri.value).takeIf(File::isFile)
                    ?: return@withContext ActionResult.Failure("Файла документа нет на диске", recoverable = false)

                reportStage("Разбираю PDF на страницы")
                val read = StringBuilder()
                var total = 0
                var readable = 0
                org.apache.pdfbox.pdmodel.PDDocument.load(source).use { document ->
                    total = document.numberOfPages
                    if (total == 0) {
                        return@withContext ActionResult.Failure(
                            "В документе не нашлось ни одной страницы",
                            recoverable = true,
                        )
                    }
                    val renderer = org.apache.pdfbox.rendering.PDFRenderer(document)
                    for (index in 0 until total) {
                        reportStage("Читаю страницу ${index + 1} из $total")
                        val page = File.createTempFile("page-", ".png").apply { deleteOnExit() }
                        javax.imageio.ImageIO.write(renderer.renderImageWithDPI(index, PAGE_DPI), "png", page)
                        val text = runCatching { readPage(page) }.getOrDefault("")
                        page.delete()

                        // Страница, на которую сервис ответил пометкой «текста нет», прочитанной
                        // не считается (#1054): иначе отписка ушла бы в текст документа и ещё
                        // сошла бы за прочитанную страницу в счёте.
                        if (!com.point.core.flow.noTextAnswer(text)) {
                            readable++
                            if (read.isNotEmpty()) read.append("\n\n")
                            read.append(text.trim())
                        }
                    }
                }
                if (readable == 0) {
                    return@withContext ActionResult.Failure(
                        "Не разобрал текст ни на одной странице",
                        recoverable = true,
                    )
                }

                val out = File(source.parentFile, source.nameWithoutExtension + " — текст.txt")
                out.writeText(read.toString())
                ActionResult.Done(
                    "Прочитано страниц: $readable из $total — текст у документа",
                    Findings(
                        features = setOf(Feature.HAS_TEXT),
                        metadata = mapOf(
                            META_OCR_TEXT_REF to out.absolutePath,
                            investigationKey(KnownCapabilities.IMAGE_TEXT) to
                                if (readable == total) {
                                    InvestigationState.FOUND.wire
                                } else {
                                    InvestigationState.INSUFFICIENTLY_INVESTIGATED.wire
                                },
                        ),
                    ),
                )
            }.getOrElse { ActionResult.Failure("Не удалось прочитать документ — он повреждён или страница не отрисовалась", recoverable = true) }
        }

    private companion object {
        const val PAGE_DPI = 200f
    }
}
