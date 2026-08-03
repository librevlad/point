package com.point.executors

import com.point.core.flow.BackgroundRemover
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ImageCompositor
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
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
 * photo → the subject over a background the user picks (#97 "заменить фон"). First tap asks for the
 * background image ([ActionResult.NeedsImage] → the host opens the photo picker); the picked URI
 * comes back as the amendment, and we cut the subject out and composite it over that background.
 * On-device; an arbitrary AI-generated scene is out of scope (free models can't do it reliably).
 */
class ReplaceBgCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "replace-bg"

    /** Не [Latency.INSTANT] (#288) — как и «Убрать фон»: та же сегментация ML Kit,
     *  докачиваемая при первом применении, плюс сведение с новым фоном. */
    override val meta = CapabilityMeta(priority = 42, latency = Latency.FAST)
    override fun label(state: ObjectState) = "Заменить фон"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("replace-bg") }
}

class ReplaceBgRealizer @Inject constructor(
    private val store: ObjectStore,
    private val remover: BackgroundRemover,
    private val compositor: ImageCompositor,
) : Realizer {
    override val capabilityId = ReplaceBgCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        // Первый тап ждёт человека у выбора фона, а не работу — стадии там быть не должно (#288).
        if (amendment == null) return ActionResult.NeedsImage("Выберите фон")
        return withContext(Dispatchers.IO) {
            runCatching {
                val background = store.ingest(amendment, "image/jpeg") // the picked photo → scratch copy
                reportStage("Отделяю объект от фона")
                val subject = remover.cutout(input.uri.value) // subject, transparent elsewhere
                reportStage("Ставлю новый фон")
                val result = compositor.composite(subject.value, background.uri.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", result, mapOf("op" to "replace-bg")))
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось заменить фон", recoverable = true) }
        }
    }
}
