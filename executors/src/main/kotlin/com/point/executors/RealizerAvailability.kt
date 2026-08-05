package com.point.executors

import com.point.core.flow.ActionAvailability
import com.point.core.flow.Realizer
import com.point.core.model.CapabilityId
import javax.inject.Inject

/**
 * Ответ на «есть ли чем это выполнить» — собранный из тех же реализаторов, что раздаёт `@IntoSet`.
 *
 * Ничего не запускает и запустить не может: наружу отдаётся строка причины, а не реализация. Это и
 * есть цена, за которую инвариант «Capability ≠ Realizer» остаётся целым, пока первый экран узнаёт
 * правду о доступности (#528).
 *
 * **Молчащий гейт ничего не меняет.** Реализация, погасившая себя без объяснения, оставляет
 * действие на экране ровно там, где оно было. Иначе правка сама завела бы новую тишину: действие
 * пропадало бы без единого слова, а человек не мог бы даже спросить почему. Убираем со стены
 * только то, чему есть что сказать, — договор записан в [Realizer.unavailableReason].
 */
class RealizerAvailability @Inject constructor(
    realizers: Set<@JvmSuppressWildcards Realizer>,
) : ActionAvailability {

    // Тот же порядок, что у `DefaultResolver`: объясняет себя предпочтительный реализатор, а не
    // случайный из множества. Иначе причина менялась бы от запуска к запуску.
    private val byCapability: Map<CapabilityId, List<Realizer>> =
        realizers.groupBy { it.capabilityId }
            .mapValues { (_, candidates) -> candidates.sortedBy { it.meta.priority } }

    override fun blockerFor(id: CapabilityId): String? {
        // Способность без единого реализатора — не наш случай: такую надо чинить в модуле, а не
        // прятать от человека. Тап по ней и так упрётся в честную ошибку резолвера.
        val candidates = byCapability[id] ?: return null
        if (candidates.any { it.isAvailable() }) return null
        return candidates.firstNotNullOfOrNull { it.unavailableReason() }
    }
}
