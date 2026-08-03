package com.point.executors

import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Latency
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState
import javax.inject.Inject

/**
 * The Flow Graph, derived from the set of capabilities (Hilt multibinding). The
 * bubbles for a state ARE the accepting capabilities, ranked by the [BubblePolicy]
 * — no stored transition table. `produces == null` (e.g. AI) falls back to the
 * same state as an advisory edge; the real next state is re-derived after run.
 */
class DefaultCapabilityRegistry @Inject constructor(
    private val capabilities: Set<@JvmSuppressWildcards Capability>,
    private val policy: BubblePolicy,
) : CapabilityRegistry {

    private val byIdMap: Map<CapabilityId, Capability> = capabilities.associateBy { it.id }

    override fun bubblesFor(state: ObjectState): List<Bubble> =
        policy.rank(state, capabilities.filter { it.accepts(state) })
            .map { c ->
                Bubble(
                    icon = c.icon,
                    title = c.label(state),
                    capabilityId = c.id,
                    expectedNextState = c.produces(state) ?: state,
                    tier = tierOf(c.meta),
                    intent = primaryIntentOf(c, state),
                )
            }

    /** #114: the visual level is meta, not taste — network beats everything, then latency. */
    private fun tierOf(meta: CapabilityMeta): BubbleTier = when {
        meta.network -> BubbleTier.AI
        meta.latency == Latency.INSTANT -> BubbleTier.INSTANT
        else -> BubbleTier.SMART
    }

    /** The one intent a bubble is grouped under on the object screen: the first the capability serves
     *  in [Intent] declaration order (same convention as [intentsFor]). */
    private fun primaryIntentOf(c: Capability, state: ObjectState): Intent {
        val served = c.intents(state)
        return Intent.entries.firstOrNull { it in served } ?: Intent.UNDERSTAND
    }

    override fun intentsFor(state: ObjectState): List<Intent> {
        val accepting = capabilities.filter { it.accepts(state) }
        return Intent.entries.filter { intent -> accepting.any { intent in it.intents(state) } }
    }

    // Near-miss capabilities (#97): not accepting now, but one signal away. Ranked by priority and
    // capped so the hint informs rather than clutters the real action set.
    //
    // #316: бюджет тратится на РАЗНЫЕ причины, а не на повтор одной. Замер: фото + связанный
    // компьютер без принтера — оба места занимали «Открыть ссылку · сначала распознайте текст» и
    // «Перевести · сначала распознайте текст», то есть одна новость, сказанная дважды, вытесняла
    // другую, и «Напечатать на ПК · на компьютере нет принтера» пропадало ровно тем молчанием,
    // от которого лечит #316 (на PDF и тексте причина видна — потому и не заметили). Поэтому
    // сначала берётся по одной подсказке на каждую причину, и только потом вторые.
    override fun latentBubblesFor(state: ObjectState): List<LatentBubble> {
        val hints = capabilities.filterNot { it.accepts(state) }
            .sortedBy { it.meta.priority }
            .mapNotNull { c -> c.missing(state)?.let { LatentBubble(c.icon, c.label(state), it) } }
        // Одна причина — одна новость: группы идут в порядке приоритета первой подсказки в каждой.
        val byReason = hints.groupBy(LatentBubble::missing).values.toList()
        val rounds = byReason.maxOfOrNull { it.size } ?: 0
        return (0 until rounds)
            .flatMap { round -> byReason.mapNotNull { it.getOrNull(round) } }
            .take(MAX_LATENT)
    }

    override fun byId(id: CapabilityId): Capability =
        byIdMap[id] ?: error("No capability registered for id=${id.value}")

    private companion object {
        /** Show at most this many "почти доступно" hints — negotiation informs, it doesn't clutter.
         *  Число строк, а не причин: список остаётся ровно таким же коротким, как был. */
        const val MAX_LATENT = 2
    }
}
