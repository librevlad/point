package com.point.executors

import com.point.core.flow.AudioLevel
import com.point.core.flow.Capability
import com.point.core.flow.GraphState
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.SpeechToText
import com.point.core.flow.TextKeeper
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import java.io.File
import javax.inject.Inject

/**
 * «Расшифровать» живёт в `:core:flow` — одно действие на телефон и компьютер (#1379).
 *
 * Здесь — только двери Hilt и орган телефона: слова ложатся в рабочую копию объекта. Члены
 * переписаны руками, а не `by`-делегатом: kotlin-делегирование в `@Inject`-классе роняет KSP
 * всего модуля россыпью «error.NonExistentClass» без указания на виновника.
 */
class TranscribeCapabilityOnPhone @Inject constructor(readiness: SpeechReadiness) : Capability {
    private val inner = com.point.core.flow.TranscribeCapability(readiness)
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

class TranscribeRealizerOnPhone @Inject constructor(
    store: ObjectStore,
    speech: SpeechToText,
    readiness: SpeechReadiness,
    level: AudioLevel,
) : Realizer {
    private val inner = com.point.core.flow.TranscribeRealizer(
        speech,
        readiness,
        level,
        TextKeeper { _, text -> store.newScratchFile("txt").value.also { File(it).writeText(text) } },
    )
    override val capabilityId get() = inner.capabilityId
    override val meta get() = inner.meta
    override fun isAvailable(): Boolean = inner.isAvailable()
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun unavailableReason(): String? = inner.unavailableReason()
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        inner.perform(input, amendment)
    override suspend fun preview(input: PointObject): Preview? = inner.preview(input)
}
