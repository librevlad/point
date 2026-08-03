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
 * COLLECTION of photos → one clean, black-and-white PDF — the killer loop in a
 * single gesture: photograph the pages, share them all to Point, get one tidy
 * scan. Each page runs through [scanPage] — OpenCV detect + perspective + adaptive threshold
 * when available (deskewed like the single "Скан", #45), else the pure Otsu [ScanFilter] — and
 * the pages are assembled by [imagesToPdf].
 */
class ScanPdfCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "scan"

    /** Не [Latency.INSTANT] (#288): самое долгое действие Point — каждая страница ещё и прогоняется
     *  через [scanPage]. Но работа растёт с пачкой, а не длится всегда, — поэтому она говорит
     *  на объекте («Страница N» за страницей), а не забирает экран ради двух снимков. */
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Сканировать в PDF"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.PDF)

    companion object { val ID = CapabilityId("scan-pdf") }
}

class ScanPdfRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = ScanPdfCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                imagesToPdf(
                    store,
                    File(input.uri.value),
                    name = "скан.pdf",
                    op = "scan-pdf",
                    process = ::scanPage,
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка сканирования в PDF", recoverable = true) }
        }
}
