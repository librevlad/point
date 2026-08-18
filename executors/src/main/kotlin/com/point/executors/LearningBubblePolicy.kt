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
        order(state, candidates, intent = null)

    /**
     * Тот же порядок, но с Intent из состояния: уместный сейчас смысл поднимается выше,
     * оставаясь ранжированием, а не фильтром (Конституция §8, ADR-0001 §14).
     */
    override fun rank(
        graph: com.point.core.flow.GraphState,
        candidates: List<Capability>,
    ): List<Capability> = order(graph.state, candidates, graph.intent, backTo(graph, candidates), graph)

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

        /**
         * Знание объекта и состояние его исследований (#1140).
         *
         * Прежде порядок считался по виду, признакам и Intent — то есть по форме входа, а не
         * по тому, что Point уже понял. Отсюда «Понять» главным после успешного «Понять» и
         * «Перевести» главным там, где читать нечего.
         */
        graph: com.point.core.flow.GraphState? = null,
    ): List<Capability> {
        val counts = usage.counts()
        val keyless = !runCatching { llm.configured }.getOrDefault(true)
        return candidates.sortedWith(
            compareBy(

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

                // Отвеченный вопрос не предлагается заново (#1010, ADR-0001 §9): успешно
                // выполненное исследование уходит вниз, а не остаётся главным действием.
                { if (graph != null && alreadyAnswered(it, graph)) 1 else 0 },

                // Действию, которому нечем работать, не быть первым (#996): оно само
                // говорит, чего ему не хватает, — и уступает тому, кто это даёт.
                { if (it.missing(state) != null) 1 else 0 },

                // Дальше — общий хвост обоих устройств (#840): намерение, приоритет, имя.
                // Своя ступень здесь одна: приоритет смягчается тем, как часто человек берёт
                // это действие.
                { if (intent == null || intent in it.intents(state)) 0 else 1 },
                { effectivePriority(it, counts) },
                { it.id.value },
            ),
        )
    }

    /** Вопрос этого действия уже закрыт находкой для этого объекта. */
    private fun alreadyAnswered(c: Capability, graph: com.point.core.flow.GraphState): Boolean =
        com.point.core.flow.investigationStateOf(graph.obj.metadata, c.id) ==
            com.point.core.flow.InvestigationState.FOUND

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
