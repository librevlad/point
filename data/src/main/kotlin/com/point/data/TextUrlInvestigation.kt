package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class TextUrlInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.INSTANT,
        mayYield = setOf(Feature.HAS_URL),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("url")
    }
}

class TextUrlInvestigationRealizer @Inject constructor() : Realizer {

    override val capabilityId = TextUrlInvestigation.ID

    override val meta = com.point.core.flow.RealizerMeta(actor = "url-rules")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {
        val head = readHead(obj.uri.value)
        val url = URL_REGEX.find(head)?.value ?: return@withContext Findings()
        Findings(setOf(Feature.HAS_URL), mapOf(META_ENTITY_PREFIX + "url" to url))
    }

    private fun readHead(path: String, limit: Int = 64 * 1024): String {
        val file = File(path)

        if (!file.isFile) error(com.point.core.flow.NO_TEXT_PAYLOAD)
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


