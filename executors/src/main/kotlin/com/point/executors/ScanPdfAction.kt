package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject


class ScanPdfRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = com.point.core.flow.ScanPdfCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                imagesToPdf(
                    store,
                    input,
                    name = tr("скан.pdf"),
                    op = "scan-pdf",
                    straighten = ::scanPage,
                    wholeFrame = ::wholeFramePage,
                )
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Ошибка сканирования в PDF"), recoverable = true) }
        }
}

/** Дверь Hilt: «ScanPdfCapability» живёт в `:core:flow` (#1379). Члены руками, а не `by`: KSP. */
class ScanPdfCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.ScanPdfCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: com.point.core.flow.GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: com.point.core.flow.GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): com.point.core.model.ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<com.point.core.model.Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}
