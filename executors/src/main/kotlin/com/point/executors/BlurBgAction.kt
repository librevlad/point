package com.point.executors

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ImageCompositor
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

/**
 * photo → the subject sharp over a blurred version of the same photo (portrait effect). A
 * self-contained "replace background" that needs no second image: cut the subject out, blur the
 * original, composite. On-device.
 */
class BlurBgCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "blur"
    override val meta = CapabilityMeta(priority = 41)
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

    /**
     * Три стадии, потому что шага правда три (#288), и каждый — своя тяжёлая работа над целым
     * кадром: модель ML Kit (на первом применении ещё и качается), размытие всей фотографии,
     * сведение. Слово говорится ровно перед тем вызовом, который оно называет, — не «примерно
     * посередине»: иначе это опять отсчёт по часам, а не рассказ о происходящем.
     */
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Отделяю объект от фона")
                val subject = remover.cutout(input.uri.value) // sharp subject, transparent elsewhere
                reportStage("Размываю фон")
                val blurred = compositor.blur(input.uri.value) // blurred copy of the whole photo
                reportStage("Собираю снимок")
                val result = compositor.composite(subject.value, blurred.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", result, mapOf("op" to "blur-bg")))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось размыть фон", recoverable = true) }
        }
}
