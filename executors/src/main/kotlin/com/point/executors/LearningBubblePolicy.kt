package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityUsage
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import javax.inject.Inject
import kotlin.math.min

class LearningBubblePolicy @Inject constructor(
    private val usage: CapabilityUsage,
    private val llm: com.point.core.flow.LlmClient,
) : BubblePolicy {

    override fun rank(state: ObjectState, candidates: List<Capability>): List<Capability> =
        order(
            state,
            candidates,
            intent = null,
            nothingToRead = com.point.core.flow.nothingToRead(state, emptyMap()),
        )

    /**
     * Тот же порядок, но с Intent из состояния: уместный сейчас смысл поднимается выше,
     * оставаясь ранжированием, а не фильтром (Конституция §8, ADR-0001 §14).
     */
    override fun rank(
        graph: com.point.core.flow.GraphState,
        candidates: List<Capability>,
    ): List<Capability> = order(
        graph.state,
        candidates,
        graph.intent,
        backTo(graph, candidates),
        com.point.core.flow.nothingToRead(graph.state, graph.facts),
        com.point.core.flow.alreadyUnderstood(graph.facts),
    )

    /**
     * Действия, возвращающие исходник, из которого объект и получен (#925). Стоять первым
     * такому нельзя: человек заплатит квотой и временем за то, что у него уже есть.
     */
    private fun backTo(
        graph: com.point.core.flow.GraphState,
        candidates: List<Capability>,
    ): Set<CapabilityId> {
        val source = com.point.core.flow.inverseSourceKind(graph) ?: return emptySet()
        return candidates
            .filter { com.point.core.flow.givesBackTheSource(it, graph, source) }
            .mapTo(mutableSetOf()) { it.id }
    }

    private fun order(
        state: ObjectState,
        candidates: List<Capability>,
        intent: com.point.core.model.Intent?,
        backToSource: Set<CapabilityId> = emptySet(),
        nothingToRead: Boolean = false,
        understood: Boolean = false,
    ): List<Capability> {
        val counts = usage.counts()
        val keyless = !runCatching { llm.configured }.getOrDefault(true)

        // Про негодный объект уже известно, что содержимое не читается (#994): чтение остаётся
        // в списке — Intent не убирает действия (Конституция §8), — но первым и подсвеченным
        // стоять не может. Очевидное на битом файле — не «найти суммы», а взять целый файл.
        return candidates.sortedWith(
            compareBy(

                { if (nothingToRead && com.point.core.model.Intent.UNDERSTAND in it.intents(state)) 1 else 0 },

                // Понятое не предлагается понять заново (#1010): суть объекта уже в графе, и
                // лучший следующий шаг — воспользоваться понятым. Действие не исчезает.
                { if (understood && it.id == UnderstandCapability.ID) 1 else 0 },

                // Действие, которому нужен текст, ждёт текста (#996): на PDF без единой
                // прочитанной строки главным и подсвеченным стояло «Перевести», а «Извлечь
                // текст» — шаг, открывающий всё остальное, — вторым и без подсветки.
                { if (it.meta.needsText && !hasText(state)) 1 else 0 },

                // Обратное преобразование — вниз (#925): не прячем, но и не предлагаем первым.
                { if (it.id in backToSource) 1 else 0 },

                { if (keyless && it.meta.auth) 1 else 0 },

                // Shape #644: главная граница Point видима всегда — при живой паре
                // (без неё accepts «pc» не пропускает) «На компьютер» не тонет в фолде.
                { if (it.id == PcCapability.ID) 0 else 1 },

                // Shape #642: облачное чтение — главное только когда локально не вышло.
                // Богато прочитанное фото (HAS_TEXT) опускает его ниже локальных путей;
                // слабое чтение — рукопись, мусор — оставляет облако первым.
                { if (cloudReadOfAlreadyRead(it, state)) 1 else 0 },

                // Дальше — общий хвост обоих устройств (#840): намерение, приоритет, имя.
                // Своя ступень здесь одна: приоритет смягчается тем, как часто человек берёт
                // это действие.
                { if (intent == null || intent in it.intents(state)) 0 else 1 },
                { effectivePriority(it, counts) },
                { it.id.value },
            ),
        )
    }

    /**
     * Есть ли у объекта текст, с которым можно работать (#996): сам объект текстовый или
     * текст с него уже прочитан.
     */
    private fun hasText(state: ObjectState): Boolean =
        state.kind == com.point.core.model.ObjectKind.TEXT ||
            state.has(com.point.core.model.Feature.HAS_TEXT)

    private fun cloudReadOfAlreadyRead(c: Capability, state: ObjectState): Boolean =
        state.kind == com.point.core.model.ObjectKind.IMAGE &&
            state.has(com.point.core.model.Feature.HAS_TEXT) &&
            c.meta.cost == com.point.core.flow.Cost.PAID &&
            c.meta.network &&
            c.produces(state)?.kind == com.point.core.model.ObjectKind.TEXT

    private fun effectivePriority(c: Capability, counts: Map<CapabilityId, Int>): Int =
        c.meta.priority - min(counts[c.id] ?: 0, MAX_BOOST)

    private companion object {

        const val MAX_BOOST = 25
    }
}
