package com.point.executors

import com.point.core.flow.DropLink
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.TextKeeper
import com.point.core.model.ActionResult
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import java.io.File
import javax.inject.Inject

/**
 * «Дать ссылку» живёт в `:core:flow` — одно действие на телефон и компьютер (#1379).
 *
 * Здесь — только дверь Hilt и орган телефона: ссылка ложится файлом в рабочую копию объекта.
 * Члены переписаны руками, а не `by`-делегатом: kotlin-делегирование в `@Inject`-классе роняет
 * KSP всего модуля россыпью «error.NonExistentClass» без указания на виновника.
 */
class DropLinkRealizerOnPhone @Inject constructor(
    store: ObjectStore,
    drop: DropLink,
) : Realizer {
    private val inner = com.point.core.flow.DropLinkRealizer(
        drop,
        TextKeeper { _, link -> store.newScratchFile("txt").value.also { File(it).writeText(link) } },
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
