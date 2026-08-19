package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject

interface LlmClient {

    suspend fun run(obj: PointObject, prompt: String): ResultObject

    /**
     * Тот же вопрос, но сильнее: заход, который старается взять ДРУГОЙ сервис (#1010).
     *
     * «Понять» может бесконечно обогащать граф (решение владельца): следующий виток идёт
     * другой моделью и улучшает результат обычным merge. [avoidServices] — кто уже отвечал;
     * одиночный клиент второго исполнителя не имеет и честно отвечает собой.
     */
    suspend fun run(obj: PointObject, prompt: String, avoidServices: Set<String>): ResultObject =
        run(obj, prompt)

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
