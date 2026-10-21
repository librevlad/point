package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.Capability
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.tr
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Способность живёт в `:core:flow` — одна на телефон и компьютер (#1379). Здесь только дверь
 * Hilt; члены переписаны руками, а не `by`-делегатом: kotlin-делегирование в `@Inject`-классе
 * роняет KSP всего модуля.
 */
class CleanMetadataCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.CleanMetadataCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
}

class CleanMetadataRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {

    override val capabilityId = com.point.core.flow.CleanMetadataCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(tr("Читаю снимок"))
                // Кадр ставится, как его сняли: метка поворота уходит вместе с остальными
                // записями, и без разворота портретный снимок выходил лежащим на боку.
                // Ужимать при этом нечего — очищены записи, а не содержимое.
                val source = Bitmaps.uprightFrame(input.uri.value, maxPx = Int.MAX_VALUE)?.bitmap
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
                reportStage(tr("Убираю служебные записи"))
                val ref = store.newScratchFile("jpg")
                File(ref.value).outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                source.recycle()

                ActionResult.Success(
                    ResultObject(
                        ObjectKind.IMAGE,
                        "image/jpeg",
                        ref,
                        mapOf("op" to "clean-metadata", "name" to com.point.core.flow.cleanedPhotoName(input.metadata["name"])),
                    ),
                )
            }.getOrElse {
                ActionResult.Failure(it.message ?: tr("Очистить не вышло"), recoverable = true)
            }
        }

    private companion object { const val QUALITY = 95 }
}
