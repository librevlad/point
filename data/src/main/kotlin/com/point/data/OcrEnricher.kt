package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.stripStatusBar
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.documentType
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.EntityExtractor
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_UPSCALE
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.readingModeOf
import com.point.core.flow.ObjectStore
import com.point.core.flow.amountFacts
import com.point.core.flow.geoFacts
import com.point.core.flow.weaklyRead
import com.point.core.flow.meterFacts
import com.point.core.flow.receiptFacts
import com.point.core.flow.trackFacts
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class OcrEnricher @Inject constructor(
    private val store: ObjectStore,
    private val recognizer: AtomRecognizer,
    private val extractor: EntityExtractor,
) : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.SLOW,
        mayYield = setOf(
            Feature.HAS_TEXT, Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS,
            Feature.HAS_DATE, Feature.HAS_CARD, Feature.HAS_URL, Feature.HAS_WORD_LAYER,
        ),

        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE, KIND_IDENTIFIER),
        label = "Распознаю текст…",
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {

        val read = runCatching { recognizer.read(obj) }.getOrNull()
        val layer = read ?: AtomLayer(emptyList())

        val atomsRef = if (layer.atoms.isNotEmpty()) {
            store.newScratchFile("atoms.tsv").also { File(it.value).writeText(AtomCodec.encode(layer)) }
        } else {
            null
        }

        val mode = read?.takeIf { it.incomplete == null }?.let { readingModeOf(it) }

        val zoom = layer.transform?.upscale?.takeIf { it > 1 }?.toString()
        val evidenceOnly = EnrichmentDelta(

            features = if (atomsRef != null) setOf(Feature.HAS_WORD_LAYER) else emptySet(),
            metadata = buildMap {
                atomsRef?.let { put(META_OCR_ATOMS_REF, it.value) }
                mode?.let { put(META_READING_MODE, it.name) }
                zoom?.let { put(META_READ_UPSCALE, it) }
            },
        )
        val raw = layer.text

        if (raw.isBlank() || weaklyRead(layer)) return@withContext evidenceOnly

        val text = stripStatusBar(raw)

        val entities = entityDelta(obj, extractor.extract(text.take(MAX_CHARS)), text.take(MAX_CHARS))

        val trackMeta = trackFacts(text.take(MAX_CHARS))
        val (identifiers, idRelations) = identifierObjects(obj, text.take(MAX_CHARS), trackMeta)
        val url = URL_REGEX.find(text)?.value
        val ref = store.newScratchFile("txt")
        File(ref.value).writeText(text)
        EnrichmentDelta(
            features = entities.features + Feature.HAS_TEXT +
                (if (atomsRef != null) setOf(Feature.HAS_WORD_LAYER) else emptySet()) +
                if (url != null) setOf(Feature.HAS_URL) else emptySet(),
            metadata = buildMap {
                putAll(entities.metadata)
                if (url != null) putIfAbsent(META_ENTITY_PREFIX + "url", url)

                documentType(text)?.let { putIfAbsent(META_SEMANTIC_TYPE, it) }

                putAll(trackMeta)

                putAll(meterFacts(text.take(MAX_CHARS)))
                putAll(geoFacts(text.take(MAX_CHARS)))

                putAll(amountFacts(text.take(MAX_CHARS)))
                putAll(receiptFacts(text.take(MAX_CHARS)))
                mode?.let { put(META_READING_MODE, it.name) }
                zoom?.let { put(META_READ_UPSCALE, it) }
                put(META_OCR_TEXT_REF, ref.value)
                atomsRef?.let { put(META_OCR_ATOMS_REF, it.value) }
            },

            objects = entities.objects + identifiers,
            relations = entities.relations + idRelations,
        )
    }

    private companion object {
        const val MAX_CHARS = 20_000
        val URL_REGEX = Regex("""https?://\S+""")
    }
}
