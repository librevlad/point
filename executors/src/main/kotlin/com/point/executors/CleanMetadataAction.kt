package com.point.executors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Убрать из снимка то, что он рассказывает о себе (#547).
 *
 * Владелец 05.08.2026: «не надо перезапись, это в следующий объект просто». Point не правит
 * принесённое — он рождает новое: исходный объект остаётся как был, а очищенный снимок
 * становится самостоятельным объектом со своими действиями.
 *
 * Предупреждений и уговоров нет: Point показывает, что есть, и даёт инструмент; решает человек.
 */
class CleanMetadataCapability @Inject constructor() : Capability {

    override val id = ID

    override val icon = "cutout"

    override val meta = CapabilityMeta(priority = 46, latency = Latency.FAST)

    override fun label(state: ObjectState) = "Очистить метаданные"

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.IMAGE && (state.has(Feature.HAS_SHOT_AT) || state.has(Feature.HAS_GEO))

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.IMAGE, "тот же снимок без служебных записей")

    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("clean-metadata") }
}

class CleanMetadataRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {

    override val capabilityId = CleanMetadataCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Читаю снимок")
                val source = BitmapFactory.decodeFile(input.uri.value)
                    ?: return@withContext ActionResult.Failure(
                        // Байты не разобрались в снимок — сигнал назван, а не передан
                        // молчанием: словарь и годность объекта читают один разбор (#1258).
                        com.point.core.flow.readerFailure(
                            com.point.core.flow.READER_NOT_DECODED,
                            input.state.kind,
                        ),
                        recoverable = false,
                    )

                // Пиксели переписываются заново — вместе со снимком не переезжает ни одна
                // служебная запись. Картинка та же: очищены записи, а не содержимое.
                reportStage("Убираю служебные записи")
                val ref = store.newScratchFile("jpg")
                File(ref.value).outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                source.recycle()

                ActionResult.Success(
                    ResultObject(
                        ObjectKind.IMAGE,
                        "image/jpeg",
                        ref,
                        mapOf("op" to "clean-metadata", "name" to cleanName(input.metadata["name"])),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure(it.message ?: "Очистить не вышло", recoverable = true)
            }
        }

    private fun cleanName(name: String?): String {
        val base = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Снимок"
        return "$base · без метаданных.jpg"
    }

    private companion object { const val QUALITY = 95 }
}
