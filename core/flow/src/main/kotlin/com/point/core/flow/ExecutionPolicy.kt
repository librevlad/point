package com.point.core.flow

import com.point.core.model.ObjectState

fun interface ExecutionPolicy {

    fun choose(state: ObjectState, candidates: List<Realizer>): List<Realizer>
}

class DefaultExecutionPolicy(

    /**
     * Человек мог заранее попросить лучшее вместо бережного (#795). Тогда порядок
     * исполнителей задаёт [yoloOrder], а годность кандидатов считается как всегда.
     */
    private val yolo: YoloMode = YoloMode.OFF,
) : ExecutionPolicy {
    override fun choose(state: ObjectState, candidates: List<Realizer>): List<Realizer> {
        val fit = candidates.filter { it.isAvailable() && it.accepts(state) }
        return if (runCatching { yolo.enabled() }.getOrDefault(false)) {
            yoloOrder(fit)
        } else {
            fit.sortedWith(compareBy({ it.meta.priority }, { it::class.java.name }))
        }
    }
}
