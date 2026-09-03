package com.point.desktop

import com.point.core.flow.TEXT_NOT_KEPT
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.Latency
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.Realizer
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

    // Имя работы — из общего словаря: одна работа на двух устройствах зовётся одинаково (#1254).
    companion object { val ID = KnownCapabilities.READ_DOCUMENT }
}

class PcReadDocumentRealizer(

    /** Чтение одной страницы — существующий облачный путь, за швом для тестов без сети. */
    private val readPage: suspend (File) -> String,
) : Realizer {
    override val capabilityId = PcReadDocumentCapability.ID

    // Страницы читает тот же облачный читатель, что и одиночный снимок (`Main.kt` даёт сюда
    // `PcCloudOcrRealizer.readFrame`), — и подписывается прочитанное его именем (#1273).
    override val meta = com.point.core.flow.RealizerMeta(
        kind = com.point.core.flow.RealizerKind.CLOUD,
        actor = com.point.core.flow.OCR_SPACE_ACTOR,
    )

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(input.uri.value).takeIf(File::isFile)
                    ?: return@withContext ActionResult.Failure("Файла документа нет на диске", recoverable = false)

                reportStage("Разбираю PDF на страницы")
                val read = StringBuilder()
                var total = 0
                var readable = 0
                var broken = 0
                var brokenSaid: String? = null
                org.apache.pdfbox.pdmodel.PDDocument.load(source).use { document ->
                    total = document.numberOfPages
                    if (total == 0) {
                        return@withContext ActionResult.Failure(
                            readerFailure(READER_NO_PAGES, ObjectKind.PDF),
                            recoverable = true,
                        )
                    }
                    val renderer = org.apache.pdfbox.rendering.PDFRenderer(document)
                    for (index in 0 until total) {
                        reportStage("Читаю страницу ${index + 1} из $total")
                        val page = File.createTempFile("page-", ".png").apply { deleteOnExit() }
                        javax.imageio.ImageIO.write(renderer.renderImageWithDPI(index, PAGE_DPI), "png", page)

                        // Отказ сервиса на странице — не пустая страница (#1255). Прежде
                        // «getOrDefault("")» превращал 401 и таймаут в страницу без текста:
                        // документ, где сервис не ответил ни разу, получал приговор «не
                        // разобрал текст», а причина не доезжала до человека вовсе.
                        val text = runCatching { readPage(page) }.getOrElse { trouble ->
                            broken++
                            brokenSaid = brokenSaid
                                ?: (ownWordsOf(trouble) ?: readerFailure(trouble.message, ObjectKind.PDF))
                            null
                        }
                        page.delete()

                        // Страница, на которую сервис ответил пометкой «текста нет», прочитанной
                        // не считается (#1054): иначе отписка ушла бы в текст документа и ещё
                        // сошла бы за прочитанную страницу в счёте.
                        if (text != null && !noTextAnswer(text)) {
                            readable++
                            if (read.isNotEmpty()) read.append("\n\n")
                            read.append(text.trim())
                        }
                    }
                }

                // Правило «сколько прочитано → в каком состоянии вопрос» — общее с телефоном (#1254).
                val outcome = pagesRead(total, readable, broken, brokenSaid)
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

                // Записать прочитанное — своя работа со своей бедой (#995): осечка записи не
                // должна выходить человеку отказом «документ повреждён» — страницы прочитаны.
                val out = keepTextBesideDocument(source, read.toString())
                    ?: return@withContext ActionResult.Failure(TEXT_NOT_KEPT, recoverable = true)
                ActionResult.Done(
                    outcome.said,
                    Findings(
                        features = setOf(Feature.HAS_TEXT),
                        metadata = mapOf(
                            META_OCR_TEXT_REF to out.absolutePath,
                            investigationKey(KnownCapabilities.IMAGE_TEXT) to state.wire,
                        ),
                    ),
                )

                // Своё слово того слоя, который видел беду, — наружу как есть (#1225/#1254):
                // одна фраза «повреждён или страница не отрисовалась» накрывала и отказ
                // сервиса, и осечку отрисовки, и битый файл.
            }.getOrElse {
                ActionResult.Failure(
                    ownWordsOf(it) ?: readerFailure(it.message, ObjectKind.PDF),
                    recoverable = true,
                )
            }
        }

    private companion object {
        const val PAGE_DPI = 200f
    }
}
