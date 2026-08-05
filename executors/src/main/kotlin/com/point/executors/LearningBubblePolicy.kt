package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityUsage
import com.point.core.flow.PinnedActions
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import javax.inject.Inject
import kotlin.math.min

/**
 * Learnable ranking: like the deterministic default (sort by meta.priority, then id)
 * but each capability's *effective* priority is lowered by how often the user has
 * applied it (capped), so frequently-used bubbles drift forward — even past a tier.
 * With no usage yet it is byte-for-byte the default order, so behaviour degrades
 * gracefully. The training signal is the flow journal via [CapabilityUsage]; swapping
 * in an ML/LLM policy later touches neither the registry nor the UI.
 */
class LearningBubblePolicy @Inject constructor(
    private val pins: PinnedActions,
    private val usage: CapabilityUsage,
    private val llm: com.point.core.flow.LlmClient,
) : BubblePolicy {

    override fun rank(state: ObjectState, candidates: List<Capability>): List<Capability> {
        val counts = usage.counts()
        // #66 user rule: the pinned action wins over both priority and learned usage.
        val pinned = runCatching { pins.pinnedFor(state.kind) }.getOrNull()
        val keyless = !runCatching { llm.configured }.getOrDefault(true)
        return candidates.sortedWith(
            compareBy(
                { if (it.id == pinned) 0 else 1 },
                // Пока ключа нет, «Понять», «Перевести» и прочее, что без него молчит, не встаёт
                // главным действием. Раньше вставало: после того как Point сам прочитал
                // фотографию, наверх поднималось платное сетевое действие, а работавшее
                // «Распознать текст» уезжало вниз — и человек шёл за подсветкой в отказ.
                // Способность не прячется: она остаётся в списке, просто ниже работающего.
                { if (keyless && it.meta.auth) 1 else 0 },
                { effectivePriority(it, counts) },
                { it.id.value },
            ),
        )
    }

    private fun effectivePriority(c: Capability, counts: Map<CapabilityId, Int>): Int =
        c.meta.priority - min(counts[c.id] ?: 0, MAX_BOOST)

    private companion object {
        /** Cap on how far usage may pull a capability forward — keeps priority meaningful. */
        const val MAX_BOOST = 25
    }
}
