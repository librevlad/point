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
import javax.inject.Inject

class ScanPdfCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "scan"

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
                    input,
                    name = "скан.pdf",
                    op = "scan-pdf",
                    straighten = ::scanPage,
                    wholeFrame = ::wholeFramePage,
                )
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка сканирования в PDF", recoverable = true) }
        }
}
