package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Sharer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject
import javax.inject.Inject

/** Any single object -> system Share sheet. Terminal (no new object). */
class ShareCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "share"
    override val meta = CapabilityMeta(priority = 80)
    override fun label(state: ObjectState) = "Поделиться"
    override fun accepts(state: ObjectState) = state.kind.isFileBacked
    override fun produces(state: ObjectState) = state

    companion object { val ID = CapabilityId("share") }
}

class ShareRealizer @Inject constructor(
    private val sharer: Sharer,
) : Realizer {
    override val capabilityId = ShareCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            sharer.share(input)
            ActionResult.Done("Открыт диалог «Поделиться»")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось поделиться", recoverable = true) }
}
