package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.documentType
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class DocumentTypeInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("document-type")
    }
}

class DocumentTypeInvestigationRealizer @Inject constructor() : Realizer {

    override val capabilityId = DocumentTypeInvestigation.ID

    override val meta = com.point.core.flow.RealizerMeta(actor = "doc-type-rules")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {
        val file = File(obj.uri.value)

        if (!file.isFile) com.point.core.flow.ownWords(com.point.core.flow.NO_TEXT_PAYLOAD)
        val text = file.readText().take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)
        val type = documentType(text) ?: return@withContext Findings()
        Findings(metadata = mapOf(META_SEMANTIC_TYPE to type))
    }

    private companion object {
    }
}


