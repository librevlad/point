package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.EntityExtractor
import com.point.core.flow.asFeature
import com.point.core.flow.asMetaKey
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Flags actionable entities in a TEXT object (on-device via [EntityExtractor]) so targeted actions —
 * Позвонить, Сообщение, Написать письмо — appear as bubbles after first paint. Mirrors
 * [TextUrlEnricher]; works on OCR'd screenshots too, since OCR yields a TEXT object this runs on.
 */
class EntityEnricher @Inject constructor(
    private val extractor: EntityExtractor,
) : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.FAST,
        mayYield = setOf(
            Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS, Feature.HAS_DATE, Feature.HAS_CARD,
        ),
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val text = runCatching { File(obj.uri.value).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
            .take(MAX_CHARS)
        if (text.isBlank()) return@withContext EnrichmentDelta()
        entityDelta(extractor.extract(text))
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}

/** Entities → one delta: features to flag + understood facts (the first value per kind,
 *  `entity.*`) for the «Point понял» checklist. Shared by the text and OCR enrichers. */
internal fun entityDelta(entities: List<com.point.core.flow.Entity>): EnrichmentDelta {
    val features = entities.mapNotNullTo(mutableSetOf()) { it.type.asFeature() }
    val facts = buildMap {
        entities.forEach { e -> e.type.asMetaKey()?.let { key -> putIfAbsent(key, e.value) } }
    }
    return EnrichmentDelta(features, facts)
}
