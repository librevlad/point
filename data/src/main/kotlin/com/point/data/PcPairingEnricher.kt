package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.PC_SCHEME
import com.point.core.flow.parsePcPairing
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A `point-pc://` payload arriving as TEXT (typed, shared, or read-qr'd from a photo
 * of the PC window) lights [Feature.HAS_PC_PAIRING] — the graph then offers
 * «Подключить компьютер» (#147). Camera-free pairing closes here.
 */
class PcPairingEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(cost = EnrichCost.INSTANT, mayYield = setOf(Feature.HAS_PC_PAIRING))

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val head = runCatching { File(obj.uri.value).readText().take(4_096) }.getOrDefault("")
        val payload = head.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(PC_SCHEME) && parsePcPairing(it) != null }
            ?: return@withContext EnrichmentDelta()
        EnrichmentDelta(setOf(Feature.HAS_PC_PAIRING), mapOf("pc.pairing" to payload))
    }
}
