package com.point.executors

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
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

/**
 * photo → the subject with a transparent background (#97 "фото → прозрачный PNG"). On-device
 * segmentation; the result is a PNG IMAGE, so save / share / open apply — and it's step 1 of
 * replacing the background (compositing onto a new one comes next).
 */
class CutoutCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "cutout"
    override val meta = CapabilityMeta(priority = 40)
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
                val ref = remover.cutout(input.uri.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", ref, mapOf("op" to "cutout")))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось убрать фон", recoverable = true) }
        }
}
