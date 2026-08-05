package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject

/**
 * Gemini behind an interface — fakeable in tests.
 *
 * Never invoked on the first screen: the LLM only runs after the user picks an
 * action, from inside the AiExecutor. The [run] result is materialised into the
 * scratch store as a new object (e.g. a markdown answer written to a `.md` file).
 */
interface LlmClient {

    suspend fun run(obj: PointObject, prompt: String): ResultObject

    /**
     * Whether this client's model can actually consume [obj]. A text-only model returns
     * false for an image, so the fallback chain skips it instead of "succeeding" with a
     * "you didn't attach an image" reply and stopping there. Defaults to true (text is
     * universal); vision routing is what makes AI on a photo reliable.
     */
    fun canHandle(obj: PointObject): Boolean = true

    /**
     * Whether this is a *strong* vision model (Gemini / Claude / the user's own key) rather than
     * a weak free one. For an image the fallback tries the strong models first — free vision
     * models garble dense, handwritten or rotated tables (#22). Ignored for text objects.
     */
    val strongVision: Boolean get() = false

    /**
     * Есть ли кому отвечать **прямо сейчас** — то есть задан ли ключ.
     *
     * Вопрос без работы и без сети: он нужен, чтобы сказать «нужен ключ» ДО тапа, а не после минуты
     * ожидания (#467). Спрашивается каждый раз, потому что ключ человек вводит на экране и минуту
     * назад его могло не быть. Умолчание `true`: клиент, у которого ключа и не спрашивают
     * (подделка в тесте, бесплатный провайдер без регистрации), ненастроенным не считается.
     */
    val configured: Boolean get() = true
}

/**
 * Тот же вопрос, что [LlmClient.configured], но заданный тем, кому нельзя дать сам клиент.
 *
 * Спрашивает его [Capability] — то, что видит UI, — чтобы сказать «нужен ключ» ДО тапа, а не
 * после минуты ожидания (#529). Отдать туда [LlmClient] значило бы положить рядом с декларацией
 * «что можно» настоящий `run()`: способность, умеющая сходить в модель, — это уже реализация, и
 * инвариант «Capability ≠ Realizer» держится ровно на том, что такой ручки у неё нет.
 *
 * Тот же приём и по тому же поводу, что [SpeechReadiness] рядом со `SpeechToText` и `PcLinks`
 * рядом с `PcTransport`: контракт отвечает «есть ли кому», а не «как».
 *
 * Спрашивается на каждый кадр, а не запоминается при сборке графа: ключ человек вводит на экране
 * ключей, и минуту назад его могло не быть. Ответ обязан быть дешёвым — чтение настроек, не сеть.
 */
fun interface AiReadiness {
    /** Задан ли ключ, которым живут действия с моделью. */
    fun keySet(): Boolean
}
