package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class VCardInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.INSTANT,
        mayYield = setOf(Feature.HAS_VCARD),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("vcard-shape")
    }
}

class VCardInvestigationRealizer @Inject constructor() : Realizer {

    override val capabilityId = VCardInvestigation.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching { findings(input) }.fold(
            onSuccess = { ActionResult.Done("", it) },

            onFailure = { ActionResult.Failure(it.message ?: FAILED, recoverable = true) },
        )

    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {

        if (obj.mime.contains("vcard", ignoreCase = true)) {
            return@withContext Findings(setOf(Feature.HAS_VCARD))
        }
        val byHead = readHead(obj.uri.value).trimStart().startsWith("BEGIN:VCARD", ignoreCase = true)
        if (byHead) Findings(setOf(Feature.HAS_VCARD)) else Findings()
    }

    private fun readHead(path: String, limit: Int = 256): String {
        val file = File(path)

        if (!file.isFile) error(NO_PAYLOAD)
        return file.inputStream().bufferedReader().use { reader ->
            val buffer = CharArray(limit)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }
    }
}

private const val FAILED = "исследование не удалось"

private const val NO_PAYLOAD = "текст объекта недоступен"
