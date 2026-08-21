package com.point.executors

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ImageCompositor
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

class BlurBgCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "blur"

    // Долгая работа, как «Убрать фон» (#1128): та же модель сегментации, те же минуты
    // на медленном телефоне — и тот же экран «Идёт N с» с «Отменить», а не тишина.
    override val meta = CapabilityMeta(priority = 41, latency = Latency.SLOW)
    override fun label(state: ObjectState) = "Размыть фон"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("blur-bg") }
}

class BlurBgRealizer @Inject constructor(
    private val remover: BackgroundRemover,
    private val compositor: ImageCompositor,
) : Realizer {
    override val capabilityId = BlurBgCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Отделяю объект от фона")
                val subject = remover.cutout(input.uri.value)
                reportStage("Размываю фон")
                val blurred = compositor.blur(input.uri.value)
                reportStage("Собираю снимок")
                val result = compositor.composite(subject.value, blurred.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", result, mapOf("op" to "blur-bg")))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось размыть фон", recoverable = true) }
        }
}
