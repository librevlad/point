package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.EntityExtractor
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.TextRecognizer
import com.point.core.flow.looksLikeOcrGarbage
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The screenshot-comes-alive step (#64): a shared IMAGE is OCR'd **in the background**
 * (on-device Tesseract — never the cloud, never a prompt) and the entities found in the
 * recognised text light up straight on the image — «Позвонить»/«Создать событие» appear
 * on a screenshot without the manual «Распознать текст» hop.
 *
 * The recognised text is written to scratch once and referenced via [META_OCR_TEXT_REF],
 * so entity realizers and the OCR capability itself reuse it instead of re-running the
 * engine. Gibberish (photos of the world, not of text) is discarded by the shared
 * [looksLikeOcrGarbage] heuristic — a photo of food simply stays a photo.
 */
class OcrEnricher @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: TextRecognizer,
    private val extractor: EntityExtractor,
) : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.SLOW,
        mayYield = setOf(
            Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS,
            Feature.HAS_DATE, Feature.HAS_CARD, Feature.HAS_URL,
        ),
        label = "Распознаю текст…",
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val text = runCatching { recognizer.recognize(obj) }.getOrDefault("")
        if (text.isBlank() || looksLikeOcrGarbage(text)) return@withContext EnrichmentDelta()

        val entities = entityDelta(extractor.extract(text.take(MAX_CHARS)))
        val url = URL_REGEX.find(text)?.value
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(text)
        EnrichmentDelta(
            features = entities.features + if (url != null) setOf(Feature.HAS_URL) else emptySet(),
            metadata = buildMap {
                putAll(entities.metadata)
                if (url != null) putIfAbsent(META_ENTITY_PREFIX + "url", url)
                put(META_OCR_TEXT_REF, ref.value)
            },
        )
    }

    private companion object {
        const val MAX_CHARS = 20_000
        val URL_REGEX = Regex("""https?://\S+""")
    }
}
