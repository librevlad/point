package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PdfImageInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
        mayYield = setOf(Feature.IS_IMAGE_PDF),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.PDF

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("pdf-image-shape")
    }
}

class PdfImageInvestigationRealizer @Inject constructor(
    private val pdfText: PdfTextExtractor,
) : Realizer {

    override val capabilityId = PdfImageInvestigation.ID

    override val meta = com.point.core.flow.RealizerMeta(actor = "pdf-pages")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    /**
     * Вопрос один: достаётся ли текст из самого файла (#933, #995).
     *
     * Пустого слоя мало: у части документов слой есть, но в нём подменена раскладка шрифта —
     * «извлечённый» текст оказывается мусором. Правило живёт в `:core:flow` и одно с
     * компьютером, который спрашивает то же самое при приёме: иначе тот же документ здесь и
     * там — разный объект с разными дверями.
     */
    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {
        val text = pdfText.extractText(obj)
        if (com.point.core.flow.pdfLayerUnusable(text)) Findings(setOf(Feature.IS_IMAGE_PDF)) else Findings()
    }
}

