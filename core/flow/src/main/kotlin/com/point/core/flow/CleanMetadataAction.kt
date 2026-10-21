package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.tr

/**
 * Убрать из снимка то, что он рассказывает о себе (#547) — одна способность на телефон и
 * компьютер (#1379, волна 2).
 *
 * Владелец 05.08.2026: «не надо перезапись, это в следующий объект просто». Point не правит
 * принесённое — он рождает новое: исходный объект остаётся как был, а очищенный снимок
 * становится самостоятельным объектом со своими действиями.
 *
 * Предупреждений и уговоров нет: Point показывает, что есть, и даёт инструмент; решает человек.
 *
 * Как переписать пиксели, решает исполнитель каждой стороны: телефон — `Bitmap`, компьютер —
 * ImageIO. Что именно снимок о себе рассказал, обе стороны узнают одним вопросом
 * ([KnownCapabilities.PHOTO_HEAD]).
 */
class CleanMetadataCapability : Capability {

    override val id = ID

    override val icon = "cutout"

    override val meta = CapabilityMeta(priority = 46, latency = Latency.FAST)

    override fun label(state: ObjectState) = tr("Очистить метаданные")

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.IMAGE && (state.has(Feature.HAS_SHOT_AT) || state.has(Feature.HAS_GEO))

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.IMAGE, tr("тот же снимок без служебных записей"))

    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("clean-metadata") }
}

/** Имя очищенного снимка — одно на оба устройства. */
fun cleanedPhotoName(name: String?): String {
    val base = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: tr("Снимок")
    return tr("{0} · без метаданных", base) + ".jpg"
}
