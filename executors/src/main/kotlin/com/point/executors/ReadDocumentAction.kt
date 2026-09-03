package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.GraphState
import com.point.core.flow.ObjectStore
import com.point.core.flow.PdfRasterizer
import com.point.core.flow.Realizer
import com.point.core.flow.TextKeeper
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import java.io.File
import javax.inject.Inject

/**
 * «Прочитать документ» живёт в `:core:flow` — одно действие на телефон и компьютер (#1379).
 *
 * Здесь — только двери Hilt и органы телефона: свой отрисовщик PDF, свой читатель, текст в
 * рабочую копию, слово о цене — «на телефоне». Члены переписаны руками, а не `by`-делегатом:
 * kotlin-делегирование в `@Inject`-классе роняет KSP всего модуля россыпью
 * «error.NonExistentClass» без указания на виновника.
 */
class ReadDocumentCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.ReadDocumentCapability(promise = "текст всех страниц · на телефоне")
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

class ReadDocumentRealizerOnPhone @Inject constructor(
    rasterizer: PdfRasterizer,
    recognizer: TextRecognizer,
    store: ObjectStore,
) : Realizer {
    private val inner = com.point.core.flow.ReadDocumentRealizer(
        rasterizer,
        recognizer,
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
