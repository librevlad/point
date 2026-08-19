package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

interface Capability {

    val id: CapabilityId

    val icon: String

    val meta: CapabilityMeta get() = CapabilityMeta()

    fun label(state: ObjectState): String

    /**
     * Имя действия по текущему знанию (#1010): отработавший виток зовётся дальше — «Понять
     * сильнее», а не тем же обещанием. По умолчанию знание на имя не влияет.
     */
    fun label(graph: GraphState): String = label(graph.state)

    fun accepts(state: ObjectState): Boolean

    /**
     * Применимость по всему состоянию: знание объекта, найденные объекты, отношения, Focus,
     * Investigation State (ADR-0001 §14, RFC §7).
     *
     * По умолчанию — прежний ответ по форме объекта. Capability, которой нужен факт,
     * отношение или Focus, переопределяет именно это, не расширяя `Feature`.
     */
    fun accepts(graph: GraphState): Boolean = accepts(graph.state)

    fun produces(state: ObjectState): ObjectState?

    fun yields(state: ObjectState): ActionYield = derivedYield(this, state)

    fun intents(state: ObjectState): Set<Intent> {
        val next = produces(state)
        return when {
            next == null -> setOf(Intent.UNDERSTAND)
            next === state -> setOf(Intent.SEND)
            next.kind == ObjectKind.TEXT -> setOf(Intent.UNDERSTAND)
            else -> setOf(Intent.PREPARE)
        }
    }

    fun missing(state: ObjectState): String? = null
}

/**
 * Главное намерение действия — то, по которому оно попадает в свою группу на экране (#879).
 *
 * Жило в телефонном реестре, и компьютер о нём не знал: там все действия получали намерение
 * по умолчанию и сливались в одну группу. Правило смотрит только на саму способность и
 * состояние объекта — Android ему не нужен.
 */
fun primaryIntentOf(capability: Capability, state: ObjectState): Intent {
    val served = capability.intents(state)
    return Intent.entries.firstOrNull { it in served } ?: Intent.UNDERSTAND
}
