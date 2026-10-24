package com.point.executors

import android.graphics.Bitmap
import com.point.core.flow.Capability
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject


class ScanRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = com.point.core.flow.ScanCapability.ID

    override val meta = RealizerMeta(priority = 90)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage(tr("Читаю снимок"))
                val src = Bitmaps.decodeUpright(input.uri.value)
                    ?: error(tr("Не удалось прочитать изображение"))
                val width = src.width
                val height = src.height
                val pixels = IntArray(width * height)
                src.getPixels(pixels, 0, width, 0, 0, width, height)

                reportStage(tr("Свожу к чёрно-белому"))
                val scanned = ScanFilter.apply(pixels)
                val output = Bitmap.createBitmap(scanned, width, height, Bitmap.Config.ARGB_8888)

                reportStage(tr("Сохраняю"))
                val ref = store.newScratchFile("png")
                File(ref.value).outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                src.recycle()
                output.recycle()

                ActionResult.Success(
                    ResultObject(ObjectKind.IMAGE, "image/png", ref, mapOf("op" to "scan")),
                )
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Ошибка сканирования"), recoverable = true) }
        }
}

/** Дверь Hilt: «ScanCapability» живёт в `:core:flow` (#1379). Члены руками, а не `by`: KSP. */
class ScanCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.ScanCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun priority(state: ObjectState): Int = inner.priority(state)
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: com.point.core.flow.GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: com.point.core.flow.GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): com.point.core.model.ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<com.point.core.model.Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}
