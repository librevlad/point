package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Peeks the head of a text object and flags [Feature.HAS_URL] if it holds a link. */
class TextUrlEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(cost = EnrichCost.INSTANT, mayYield = setOf(Feature.HAS_URL))

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val head = runCatching { readHead(obj.uri.value) }.getOrDefault("")
        val url = URL_REGEX.find(head)?.value ?: return@withContext EnrichmentDelta()
        EnrichmentDelta(setOf(Feature.HAS_URL), mapOf(META_ENTITY_PREFIX + "url" to url))
    }

    private fun readHead(path: String, limit: Int = 64 * 1024): String {
        val file = File(path)
        if (!file.exists()) return ""
        return file.inputStream().bufferedReader().use { reader ->
            val buffer = CharArray(limit)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }
    }

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
    }
}
