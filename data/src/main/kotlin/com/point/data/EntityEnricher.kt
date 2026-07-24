package com.point.data

import com.point.core.flow.Enricher
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
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

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): Set<Feature> = withContext(Dispatchers.IO) {
        val text = runCatching { File(obj.uri.value).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
            .take(MAX_CHARS)
        if (text.isBlank()) return@withContext emptySet()
        extractor.extract(text).mapNotNullTo(mutableSetOf()) { feature(it.type) }
    }

    private fun feature(type: EntityType): Feature? = when (type) {
        EntityType.PHONE -> Feature.HAS_PHONE
        EntityType.EMAIL -> Feature.HAS_EMAIL
        EntityType.ADDRESS -> Feature.HAS_ADDRESS
        EntityType.DATE_TIME -> Feature.HAS_DATE
        EntityType.PAYMENT_CARD -> Feature.HAS_CARD
        else -> null // url handled by TextUrlEnricher; money has no action yet
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}
