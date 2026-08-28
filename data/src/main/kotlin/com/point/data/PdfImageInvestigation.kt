package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
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
import java.io.File
import javax.inject.Inject

class PdfImageInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,

        // Мерка стоимости — работа исполнителя, а не объявленная лёгкость (#1240): текстовый
        // слой документа достаётся страницами, а не мгновенно. Названная цена сама включает
        // гейт по знанию: в тот же документ второй раз входят без повторного разбора.
        latency = Latency.SLOW,

        // Объявлено то, что исследование действительно приносит (#1241). Слоя нет — приходит
        // IS_IMAGE_PDF и открывается «Прочитать документ»; слой есть — приходит HAS_TEXT
        // вместе с `ocr.text.ref`, и открывается «Понять». Умолчи вторую находку — и гейт
        // `worthRunning` судил бы это исследование по одной чужой двери из двух.
        mayYield = setOf(Feature.IS_IMAGE_PDF, Feature.HAS_TEXT),
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
    private val store: ObjectStore,
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
     *
     * Слой достаётся ровно один раз (#1241). Прежде этот же разбор делался ради одного
     * булева ответа и выбрасывался, а «Перевести», «В Word» и каждая реплика разговора
     * поднимали документ заново — те же секунды за уже известное.
     */
    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {
        val text = pdfText.extractText(obj)
        if (com.point.core.flow.pdfLayerUnusable(text)) {
            return@withContext Findings(setOf(Feature.IS_IMAGE_PDF))
        }

        // Найденный слой — знание объекта, а не побочный продукт вопроса (#1241). Ложится
        // тем же ключом и тем же признаком, каким ложится любое прочтение (#995): дальше его
        // первым находит `CurrentKnowledge`, и разговор, перевод и «В Word» документ заново
        // не разбирают.
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(text)
        Findings(features = setOf(Feature.HAS_TEXT), metadata = mapOf(META_OCR_TEXT_REF to ref.value))
    }
}
