package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * «Улучшить фото» (#433, решение владельца 23.10.2026): свет, цвет, шум и резкость, а мелкий
 * снимок ещё и вдвое крупнее. Одно действие на телефон и компьютер, как «Убрать фон»:
 * отличается только орган, а делает его та же OpenCV на самом устройстве — снимок никуда
 * не уходит.
 *
 * Это превращение, а не знание: улучшенный снимок — новый объект, исходник остаётся как был.
 */

/** Орган улучшения: путь к снимку внутрь, улучшенная копия наружу. */
interface PhotoEnhancer {
    suspend fun enhance(imagePath: String): ScratchRef
}

class EnhancePhotoCapability : Capability {
    override val id = ID
    override val icon = "enhance"

    // Долгая работа, как «Убрать фон» (#1128): на медленном телефоне шумоподавление и
    // увеличение идут секунды — человеку положены «Идёт N с» и «Отменить».
    override val meta = CapabilityMeta(priority = 39, latency = Latency.SLOW)
    override fun label(state: ObjectState) = tr("Улучшить фото")
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("enhance-photo") }
}

class EnhancePhotoRealizer(
    private val enhancer: PhotoEnhancer,
    private val ready: () -> Boolean = { true },
) : Realizer {
    override val capabilityId = EnhancePhotoCapability.ID

    override fun isAvailable(): Boolean = ready()

    override fun unavailableReason(): String = tr("нужен пакет обработки снимков")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(tr("Читаю снимок"))
                val ref = enhancer.enhance(input.uri.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/jpeg", ref, mapOf("op" to "enhance-photo")))
            }.getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                ActionResult.Failure(it.message ?: ENHANCE_FAILED, recoverable = true)
            }
        }

    private companion object {
        val ENHANCE_FAILED: String get() = tr("Улучшить фото не вышло — попробуйте ещё раз")
    }
}
