package com.point.executors

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CutoutCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "cutout"

    override val meta = CapabilityMeta(priority = 40, latency = Latency.FAST)
    override fun label(state: ObjectState) = "Убрать фон"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("cutout") }
}

class CutoutRealizer @Inject constructor(
    private val remover: BackgroundRemover,
) : Realizer {
    override val capabilityId = CutoutCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Отделяю объект от фона")
                val ref = remover.cutout(input.uri.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", ref, mapOf("op" to "cutout")))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось убрать фон", recoverable = true) }
        }
}
