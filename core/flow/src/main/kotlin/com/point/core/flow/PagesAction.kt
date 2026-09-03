package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PagesCapability : Capability {
    override val id = ID
    override val icon = "pages"

    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Страницы"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.PDF
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.COLLECTION)

    companion object { val ID = CapabilityId("pdf-pages") }
}

class PagesRealizer(
    private val rasterizer: PdfRasterizer,
) : Realizer {
    override val capabilityId = PagesCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Разбираю PDF на страницы")
                val dir = rasterizer.rasterize(input)
                val count = File(dir.value).walkTopDown().count { it.isFile }
                if (count == 0) {
                    ActionResult.Failure(NOT_SPLIT, recoverable = true)
                } else {
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.COLLECTION,
                            "inode/directory",
                            dir,
                            mapOf("op" to "pages", "count" to count.toString()),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(refusal(input, it.message), recoverable = true) }
        }

    /**
     * #570: «в документе нет ни одной страницы» — это про документ, и так это и говорится.
     * Всё, чего мы не знаем точно, остаётся прежним отказом разбора, а чужой текст
     * исключения человеку не показывается.
     */
    private fun refusal(input: PointObject, reason: String?): String =
        if (READER_NO_PAGES in reason.orEmpty()) readerFailure(reason, input.state.kind) else NOT_SPLIT

    private companion object {
        const val NOT_SPLIT = "Не удалось разобрать PDF на страницы"
    }
}
