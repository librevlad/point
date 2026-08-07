package com.point.core.flow

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class ActionProgress(private val onStage: (String) -> Unit) :
    AbstractCoroutineContextElement(Key) {

    fun report(stage: String) = onStage(stage)

    companion object Key : CoroutineContext.Key<ActionProgress>
}

suspend fun reportStage(stage: String) {
    coroutineContext[ActionProgress]?.report(stage)
}
