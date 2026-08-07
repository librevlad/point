package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Viewer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class OpenCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "open"

    override val meta = CapabilityMeta(priority = 65, localOnly = true)
    override fun label(state: ObjectState) = "Открыть"
    override fun accepts(state: ObjectState) =
        state.kind.isFileBacked && state.kind != ObjectKind.URL
    override fun produces(state: ObjectState) = state

    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("open") }
}

class OpenRealizer @Inject constructor(
    private val viewer: Viewer,
) : Realizer {
    override val capabilityId = OpenCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                viewer.view(input)
                ActionResult.Done("Открываю…")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть", recoverable = true) }
        }
}
