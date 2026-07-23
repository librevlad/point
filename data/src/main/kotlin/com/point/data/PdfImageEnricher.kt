package com.point.data

import com.point.core.flow.Enricher
import com.point.core.flow.PdfTextExtractor
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Flags a PDF with no extractable text layer as [Feature.IS_IMAGE_PDF] — a scan
 * whose pages are just images. This lets the UI route away from the dead-end
 * "Извлечь текст" (which would only fail on a scan) toward a working path to
 * text: reading it with AI, or splitting it into page images to OCR.
 *
 * Reuses the same [PdfTextExtractor] the PdfRealizer uses, so "has a text layer"
 * means one consistent thing across the app: a blank extraction ⇒ image-only PDF.
 */
class PdfImageEnricher @Inject constructor(
    private val pdfText: PdfTextExtractor,
) : Enricher {

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.PDF

    override suspend fun enrich(obj: PointObject): Set<Feature> = withContext(Dispatchers.IO) {
        val text = runCatching { pdfText.extractText(obj) }.getOrDefault("")
        if (text.isBlank()) setOf(Feature.IS_IMAGE_PDF) else emptySet()
    }
}
