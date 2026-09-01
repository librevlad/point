package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CurrentKnowledge
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.model.Preview
import com.point.core.flow.Realizer
import com.point.core.flow.SpreadsheetWriter
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * «В Excel» живёт в `:core:flow` — одно действие на телефон и компьютер (#1369).
 *
 * Здесь — только дверь Hilt: ядро про `javax.inject` не знает, а `@Binds` требует класс с
 * `@Inject`-конструктором в этом модуле. Члены переписаны руками, а не `by`-делегатом:
 * kotlin-делегирование в `@Inject`-классе роняет KSP всего модуля россыпью
 * «error.NonExistentClass» без указания на виновника (родня ksp-беды с native AAR, #1256).
 */
class ExcelCapabilityOnPhone @Inject constructor(keys: AiReadiness) : Capability {
    private val inner = com.point.core.flow.ExcelCapability(keys)
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
}

class ExcelRealizerOnPhone @Inject constructor(
    providers: List<@JvmSuppressWildcards LlmClient>,
    writer: SpreadsheetWriter,
    cropper: EvidenceCropper,
    store: ObjectStore,
    known: CurrentKnowledge,
) : Realizer {
    private val inner = com.point.core.flow.ExcelRealizer(providers, writer, cropper, store, known)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}
