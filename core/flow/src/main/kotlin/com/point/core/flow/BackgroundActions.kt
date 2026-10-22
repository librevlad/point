package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * «Убрать фон», «Размыть фон» и «Заменить фон» — одни на телефон и компьютер (#1379).
 * Отличается только орган: телефон отделяет объект моделью ML Kit, компьютер — своей
 * моделью через OpenCV. Действия и слова о них одни.
 */

class CutoutCapability : Capability {
    override val id = ID
    override val icon = "cutout"

    // Долгая работа, а не быстрая (#1128): на медленном телефоне модель сегментации
    // разворачивается минутами, и «быстрое» объявление оставляло человека перед мёртвым
    // экраном — без «Идёт N с» и без «Отменить», которые положены всякой долгой работе.
    override val meta = CapabilityMeta(priority = 40, latency = Latency.SLOW)
    override fun label(state: ObjectState) = tr("Убрать фон")
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("cutout") }
}

class CutoutRealizer(
    private val remover: BackgroundRemover,
) : Realizer {
    override val capabilityId = CutoutCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(tr("Отделяю объект от фона"))
                val ref = remover.cutout(input.uri.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", ref, mapOf("op" to "cutout")))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось убрать фон"), recoverable = true) }
        }
}

class BlurBgCapability : Capability {
    override val id = ID
    override val icon = "blur"

    // Долгая работа, как «Убрать фон» (#1128): та же модель сегментации, те же минуты
    // на медленном телефоне — и тот же экран «Идёт N с» с «Отменить», а не тишина.
    override val meta = CapabilityMeta(priority = 41, latency = Latency.SLOW)
    override fun label(state: ObjectState) = tr("Размыть фон")
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("blur-bg") }
}

class BlurBgRealizer(
    private val remover: BackgroundRemover,
    private val compositor: ImageCompositor,
) : Realizer {
    override val capabilityId = BlurBgCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(tr("Отделяю объект от фона"))
                val subject = remover.cutout(input.uri.value)
                reportStage(tr("Размываю фон"))
                val blurred = compositor.blur(input.uri.value)
                reportStage(tr("Собираю снимок"))
                val result = compositor.composite(subject.value, blurred.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", result, mapOf("op" to "blur-bg")))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось размыть фон"), recoverable = true) }
        }
}

class ReplaceBgCapability : Capability {
    override val id = ID
    override val icon = "replace-bg"

    // Долгая работа, как «Убрать фон» (#1128): та же модель сегментации, те же минуты
    // на медленном телефоне — и тот же экран «Идёт N с» с «Отменить», а не тишина.
    override val meta = CapabilityMeta(priority = 42, latency = Latency.SLOW)
    override fun label(state: ObjectState) = tr("Заменить фон")
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("replace-bg") }
}

class ReplaceBgRealizer(
    private val store: ObjectStore,
    private val remover: BackgroundRemover,
    private val compositor: ImageCompositor,
) : Realizer {
    override val capabilityId = ReplaceBgCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {

        if (amendment == null) return ActionResult.NeedsImage(tr("Выберите фон"))
        return withContext(Dispatchers.IO) {
            runCatching {
                val background = store.ingest(amendment, "image/jpeg")
                reportStage(tr("Отделяю объект от фона"))
                val subject = remover.cutout(input.uri.value)
                reportStage(tr("Ставлю новый фон"))
                val result = compositor.composite(subject.value, background.uri.value)
                ActionResult.Success(ResultObject(ObjectKind.IMAGE, "image/png", result, mapOf("op" to "replace-bg")))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось заменить фон"), recoverable = true) }
        }
    }
}
