package com.point.executors

import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.TextRecognizer
import com.point.core.flow.investigationKey
import com.point.core.flow.reportStage
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File

/**
 * Чтение PDF глазами: страницы рисует растеризатор, читает движок устройства.
 *
 * Один и тот же путь у «Прочитать документ» (#1014) и у «Извлечь текст», когда текстового
 * слоя в файле нет или он нечитаем (#995). Раньше второй заворачивал папку страниц в объект
 * вида «изображение» — объект указывал на каталог и не открывался ни у кого, а отрисованная
 * страница оставалась недостижимой.
 */
internal class PagesRead(val text: String, val readable: Int, val total: Int) {

    val nothing: Boolean get() = readable == 0

    fun said(): String = "Прочитано страниц: $readable из $total — текст у документа"

    /** Прочитана часть страниц — вопрос не закрывается находкой целиком (ADR-0001 §9). */
    fun knowledge(ref: ScratchRef): Findings = Findings(
        features = setOf(Feature.HAS_TEXT),
        metadata = mapOf(
            META_OCR_TEXT_REF to ref.value,
            investigationKey(KnownCapabilities.IMAGE_TEXT) to
                if (readable == total) InvestigationState.FOUND.wire else InvestigationState.INSUFFICIENTLY_INVESTIGATED.wire,
        ),
    )
}

internal const val SPLIT_PAGES_STAGE = "Разбираю PDF на страницы"

internal suspend fun readPagesWithEyes(
    input: PointObject,
    rasterizer: PdfRasterizer,
    recognizer: TextRecognizer,
): PagesRead {
    reportStage(SPLIT_PAGES_STAGE)
    val dir = rasterizer.rasterize(input)
    val pages = File(dir.value).walkTopDown().filter { it.isFile }.sortedBy { it.name }.toList()
    if (pages.isEmpty()) return PagesRead("", 0, 0)

    val read = StringBuilder()
    var readable = 0
    pages.forEachIndexed { index, page ->
        reportStage("Читаю страницу ${index + 1} из ${pages.size}")
        val text = runCatching {
            recognizer.recognize(input.copy(mime = "image/png", uri = ScratchRef(page.absolutePath)))
        }.getOrDefault("")
        if (text.isNotBlank()) {
            readable++
            if (read.isNotEmpty()) read.append("\n\n")
            read.append(text.trim())
        }
    }
    return PagesRead(read.toString(), readable, pages.size)
}
