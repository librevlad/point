package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.QrReader
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

class QrInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
        mayYield = setOf(Feature.HAS_QR),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override fun produces(state: ObjectState) = state

    companion object {

        // Не «qr»: этот id носит действие «QR-код» (сделать QR из текста). Общий id
        // сталкивает реализаторы в резолвере — как у пары «ocr»/«Распознать текст».
        val ID = com.point.core.model.CapabilityId("qr-content")
    }
}

class QrInvestigationRealizer @Inject constructor(
    private val reader: QrReader,
) : Realizer {

    override val capabilityId = QrInvestigation.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching { findings(input) }.fold(
            onSuccess = { ActionResult.Done("", it) },

            onFailure = { ActionResult.Failure(it.message ?: FAILED, recoverable = true) },
        )

    private suspend fun findings(obj: PointObject): Findings {
        val found = reader.decode(obj.uri.value) ?: return Findings()
        return Findings(setOf(Feature.HAS_QR), mapOf(META_ENTITY_PREFIX + "qr" to found))
    }
}

private const val FAILED = "исследование не удалось"
