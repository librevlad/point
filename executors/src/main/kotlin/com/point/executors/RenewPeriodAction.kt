package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.GraphState
import com.point.core.flow.Realizer
import com.point.core.flow.SpreadsheetReader
import com.point.core.flow.SpreadsheetWriter
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import javax.inject.Inject

/**
 * Действие живёт в `:core:flow` — одно на телефон и компьютер (#1379). Здесь только двери Hilt.
 * Члены переписаны руками, а не `by`-делегатом: kotlin-делегирование в `@Inject`-классе роняет
 * KSP всего модуля россыпью «error.NonExistentClass» без указания на виновника.
 */
class RenewPeriodCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.RenewPeriodCapability()
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
    override fun missing(state: ObjectState): String? = inner.missing(state)
}

class RenewPeriodRealizerOnPhone @Inject constructor(
    sheets: SpreadsheetReader,
    writer: SpreadsheetWriter,
) : Realizer {
    private val inner = com.point.core.flow.RenewPeriodRealizer(sheets, writer)
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}
