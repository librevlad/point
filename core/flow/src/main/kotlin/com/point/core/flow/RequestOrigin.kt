package com.point.core.flow

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class RequestOrigin(val here: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestOrigin>
}

suspend fun askedHere(): Boolean = coroutineContext[RequestOrigin]?.here ?: true
