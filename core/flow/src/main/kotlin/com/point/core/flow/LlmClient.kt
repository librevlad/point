package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject

interface LlmClient {

    suspend fun run(obj: PointObject, prompt: String): ResultObject

    fun canHandle(obj: PointObject): Boolean = true

    val strongVision: Boolean get() = false

    val configured: Boolean get() = true

    /**
     * Сервис, к которому обращается этот исполнитель. Пусто — исполнитель сам
     * обходит несколько сервисов и запоминает исходы сам (#699).
     */
    val serviceId: String get() = ""
}

fun interface AiReadiness {

    fun keySet(): Boolean
}
