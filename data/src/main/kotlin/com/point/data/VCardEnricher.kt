package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class VCardEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(cost = EnrichCost.INSTANT, mayYield = setOf(Feature.HAS_VCARD))

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val byMime = obj.mime.contains("vcard", ignoreCase = true)
        val byHead = runCatching { readHead(obj.uri.value) }
            .getOrDefault("").trimStart().startsWith("BEGIN:VCARD", ignoreCase = true)
        if (byMime || byHead) EnrichmentDelta(setOf(Feature.HAS_VCARD)) else EnrichmentDelta()
    }

    private fun readHead(path: String, limit: Int = 256): String {
        val file = File(path)
        if (!file.exists()) return ""
        return file.inputStream().bufferedReader().use { reader ->
            val buffer = CharArray(limit)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }
    }
}
