package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.QrReader
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * Async peek: flags [Feature.HAS_QR] when a shared image actually contains a QR code, so "Считать QR"
 * only appears on images that have one — not on every photo. Off the ≤300 ms first paint (like the
 * other enrichers); decoding is on-device via [QrReader].
 */
class QrEnricher @Inject constructor(
    private val reader: QrReader,
) : Enricher {

    override val meta = EnricherMeta(cost = EnrichCost.FAST, mayYield = setOf(Feature.HAS_QR))

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override suspend fun enrich(obj: PointObject): EnrichmentDelta {
        val found = runCatching { reader.decode(obj.uri.value) }.getOrNull()
        return if (found != null) EnrichmentDelta(setOf(Feature.HAS_QR)) else EnrichmentDelta()
    }
}
