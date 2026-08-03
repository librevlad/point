package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * COLLECTION of images -> one multi-page PDF. The "объединить страницы" step of the
 * scan flow: several photos (or the pages of a rasterised PDF) become a single
 * document, as-is (no cleaning — that is "Сканировать в PDF"). Assembly is shared
 * with the scan variant via [imagesToPdf].
 */
class MergePdfCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "pdf"

    /** Не [Latency.INSTANT] (#288): двадцать фото с камеры декодируются и рисуются в страницы десятки
     *  секунд — и всё это время [imagesToPdf] честно считает собранные страницы вслух. */
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Объединить в PDF"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.PDF)

    companion object { val ID = CapabilityId("merge-pdf") }
}

class MergePdfRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = MergePdfCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                imagesToPdf(store, File(input.uri.value), name = "документ.pdf", op = "merge-pdf")
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка объединения в PDF", recoverable = true) }
        }
}
