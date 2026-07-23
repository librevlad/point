package com.point.data

import com.point.core.flow.Enricher
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Flags [Feature.HAS_VCARD] for a shared contact card (`.vcf`). A vCard arrives as a TEXT object
 * (mime `text/x-vcard`), which otherwise only offers raw-text actions; the tag lets
 * [com.point.executors.VCardCapability] surface "add to contacts" instead. Detected by MIME **or**
 * a `BEGIN:VCARD` head, so a card shared with a generic type (octet-stream) still lights up.
 */
class VCardEnricher @Inject constructor() : Enricher {

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): Set<Feature> = withContext(Dispatchers.IO) {
        val byMime = obj.mime.contains("vcard", ignoreCase = true)
        val byHead = runCatching { readHead(obj.uri.value) }
            .getOrDefault("").trimStart().startsWith("BEGIN:VCARD", ignoreCase = true)
        if (byMime || byHead) setOf(Feature.HAS_VCARD) else emptySet()
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
