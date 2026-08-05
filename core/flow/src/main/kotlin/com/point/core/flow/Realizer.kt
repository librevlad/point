package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import com.point.core.model.Preview

/**
 * One concrete way to perform a [Capability] — the **how**. Today every
 * capability has a single local realizer; tomorrow the same capability id may
 * have several (local, AI, cloud, ICG) and the [Resolver] picks one. Nothing
 * above the resolver (UI, graph, policy) is aware of realizers.
 */
interface Realizer {

    val capabilityId: CapabilityId

    /** Selection traits (see [RealizerMeta]). Default = a plain local realizer. */
    val meta: RealizerMeta get() = RealizerMeta()

    /**
     * Whether this realization can run right now — e.g. a cloud realizer with no
     * API key, or an offline device, returns false so the [Resolver] skips it.
     * Local realizers are available by default.
     */
    fun isAvailable(): Boolean = true

    /**
     * Почему эта реализация сегодня не работает — короткой фразой человеку (#528).
     *
     * Спрашивают только у погасшей ([isAvailable] `== false`), поэтому ответ может быть
     * константой: она про сам гейт, а не про текущий его исход.
     *
     * Отвечает тот, у кого стоит гейт: только он знает, чего не хватило. Фраза едет на первый
     * экран через [ActionAvailability], поэтому пишется языком человека («нужен пакет обработки
     * снимков»), а не именем библиотеки.
     *
     * `null` (умолчание) — объяснять нечего: либо реализация работает, либо её нечем заменить и
     * молчание честнее выдумки. Реализация, которая гасит себя [isAvailable], объяснить себя
     * **обязана**: иначе действие исчезает с экрана без единого слова, и это ровно та тишина,
     * от которой лечит весь срез.
     */
    fun unavailableReason(): String? = null

    /** Cancellable work. [amendment] is the user's optional free-text addition. */
    suspend fun perform(input: PointObject, amendment: String? = null): ActionResult

    /**
     * Optional pre-execution preview of the outcome (#97) — the parsed contact card, the event, the
     * address. Null (default) = run immediately with no confirm. Must be side-effect free.
     */
    suspend fun preview(input: PointObject): Preview? = null
}
