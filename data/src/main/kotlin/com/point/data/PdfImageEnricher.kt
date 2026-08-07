package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PdfImageEnricher @Inject constructor(
    private val pdfText: PdfTextExtractor,
) : Enricher {

    override val meta = EnricherMeta(cost = EnrichCost.FAST, mayYield = setOf(Feature.IS_IMAGE_PDF))

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.PDF

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val text = runCatching { pdfText.extractText(obj) }.getOrDefault("")
        if (text.isBlank()) EnrichmentDelta(setOf(Feature.IS_IMAGE_PDF)) else EnrichmentDelta()
    }
}
