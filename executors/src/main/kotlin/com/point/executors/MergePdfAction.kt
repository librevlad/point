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

/**
 * Способность живёт в `:core:flow` — одна на телефон и компьютер (#1379). Здесь только дверь
 * Hilt; члены переписаны руками, а не `by`-делегатом: kotlin-делегирование в `@Inject`-классе
 * роняет KSP всего модуля.
 */
class MergePdfCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.MergePdfCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
}

class MergePdfRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = com.point.core.flow.MergePdfCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                imagesToPdf(store, input, name = com.point.core.flow.MERGED_PDF_NAME, op = "merge-pdf")
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Ошибка объединения в PDF"), recoverable = true) }
        }
}
