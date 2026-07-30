package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.documentType
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * «Что это за документ» — on-device, by rule (#222, шаг 5).
 *
 * Names the object after what it is rather than what it is made of: «Посылка», not
 * «Изображение». The tag goes into `semantic.type`, where [com.point.core.ui.objectVerdict]
 * reads it for the headline and where a re-open finds it again without re-reading anything.
 *
 * TEXT only, like the other rule extractors — a screenshot reaches this through OCR, which
 * tags the image itself with the same function. No key, no network: the object's own name
 * must not depend on a quota.
 */
class DocumentTypeEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(cost = EnrichCost.FAST)

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val text = runCatching { File(obj.uri.value).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
            .take(MAX_CHARS)
        val type = documentType(text) ?: return@withContext EnrichmentDelta()
        EnrichmentDelta(metadata = mapOf(META_SEMANTIC_TYPE to type))
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}
