package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Exporter
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

/** Any single object -> shared storage (Downloads). Terminal. */
class SaveCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "save"
    override val meta = CapabilityMeta(priority = 70)
    override fun label(state: ObjectState) = "Сохранить"
    override fun accepts(state: ObjectState) = state.kind != ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = state

    companion object { val ID = CapabilityId("save") }
}

class SaveRealizer @Inject constructor(
    private val exporter: Exporter,
) : Realizer {
    override val capabilityId = SaveCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            ActionResult.Done("Сохранено: ${exporter.export(input)}")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось сохранить", recoverable = true) }
}
