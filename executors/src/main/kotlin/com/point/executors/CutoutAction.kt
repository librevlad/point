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

/**
 * photo → the subject with a transparent background (#97 "фото → прозрачный PNG"). On-device
 * segmentation; the result is a PNG IMAGE, so save / share / open apply — and it's step 1 of
 * replacing the background (compositing onto a new one comes next).
 */
class CutoutCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "cutout"

    /** Не [Latency.INSTANT] (#288): сегментация — модель ML Kit, а на ПЕРВОМ применении она
     *  ещё и докачивается. Мгновенным было объявлено действие, которое успевает сказать
     *  о себе целую фразу, — и из-за этого объявления фразы было негде показать. */
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

    /**
     * Стадия одна, но она нужна (#288): сегментация — не мгновенная арифметика, а модель ML Kit,
     * которая на ПЕРВОМ применении ещё и докачивается. Именно первый раз человек и видел голый
     * счётчик дольше всего, ничего не понимая про происходящее.
     */
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Отделяю объект от фона")
                val ref = remover.cutout(input.uri.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", ref, mapOf("op" to "cutout")))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось убрать фон", recoverable = true) }
        }
}
