package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** PDF -> a COLLECTION of its pages (one image per page). The same seam as
 *  archive-unpack: produces a first-class collection the flow continues on. */
class PagesCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "pages"
    override fun label(state: ObjectState) = "Страницы"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.PDF
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.COLLECTION)

    companion object { val ID = CapabilityId("pdf-pages") }
}

class PagesRealizer @Inject constructor(
    private val rasterizer: PdfRasterizer,
) : Realizer {
    override val capabilityId = PagesCapability.ID

    /**
     * Один в один случай «Распаковать» (#288): книга рисуется постранично в картинки — десятки
     * секунд, — а разбить ожидание честно нельзя, контракт [PdfRasterizer] отдаёт готовый каталог
     * одним вызовом и о своём ходе молчит. Назвать работу, которая правда идёт, — уже правда;
     * «Страница N из M» здесь пришлось бы выдумать, потому что счёт ведёт чужой код.
     */
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                reportStage("Разбираю PDF на страницы")
                val dir = rasterizer.rasterize(input)
                val count = File(dir.value).walkTopDown().count { it.isFile }
                if (count == 0) {
                    ActionResult.Failure("Не удалось разобрать PDF на страницы", recoverable = true)
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
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка рендера страниц", recoverable = true) }
        }
}
