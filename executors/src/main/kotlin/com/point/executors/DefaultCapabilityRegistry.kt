package com.point.executors

import com.point.core.flow.ActionAvailability
import com.point.core.flow.BubblePolicy
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Latency
import com.point.core.flow.unusableReason
import com.point.core.model.Bubble
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectState
import javax.inject.Inject

class DefaultCapabilityRegistry @Inject constructor(
    private val capabilities: Set<@JvmSuppressWildcards Capability>,
    private val policy: BubblePolicy,

    private val availability: ActionAvailability = ActionAvailability { null },

    /**
     * Есть ли сеть прямо сейчас (#569). Нет — сетевое действие называет это причиной вместо
     * обещания результата: человек видит своё положение до тапа, а не после тридцати секунд
     * ожидания. Действие при этом остаётся на месте и нажимаемо — прятать его нельзя.
     */
    private val network: com.point.core.flow.NetworkAvailability =
        com.point.core.flow.NetworkAvailability { true },

    /**
     * Режим приватности прямо сейчас (#943).
     *
     * В закрытом режиме действие обещало человеку то, чего режим не разрешает: «снимок уйдёт
     * в сервис» — в режиме, который отправку запретил. Дверь остаётся на месте и нажимаема:
     * по тапу человек узнаёт причину и может сменить режим. Меняются слова, а не список.
     */
    private val privacy: com.point.core.flow.CloudPrivacySettings = OPEN_TO_EVERYONE,
) : CapabilityRegistry {

    private val byIdMap: Map<CapabilityId, Capability> = capabilities.associateBy { it.id }

    override fun all(): Collection<Capability> = capabilities

    /**
     * Список по одной форме объекта — без его фактов, а значит и без суда о годности (#1101).
     *
     * Этим вопросом спрашивает не только экран: `DefaultEnrichment` складывает в состояние
     * ещё не полученное знание и смотрит, открылась ли новая дверь, — стоит ли ради этого
     * запускать медленное исследование. Годность там судить нечем: слов о ней в голом
     * состоянии нет, а по одной метке дверь не снимается (`offeredWhenUnfit`). И не надо:
     * исследование, чья единственная новая дверь — чтение (прочитанный QR), у негодного
     * объекта иначе молча перестало бы считаться стоящим — и знание, которое вернуло бы ему
     * чтения, не пришло бы никогда. Человеку двери считает [bubblesFor] по графу.
     */
    override fun bubblesFor(state: ObjectState): List<Bubble> =
        policy.rank(state, offered.filter { it.accepts(state) && blockerFor(it) == null })
            .map { c -> bubbleOf(c, state) }

    override fun bubblesFor(graph: com.point.core.flow.GraphState): List<Bubble> {
        val reason = graph.unusableReason()

        // Действие, возвращающее исходник, из которого объект получен, не прячется, а уходит
        // вниз и говорит об этом (#925).
        val source = com.point.core.flow.inverseSourceKind(graph)
        return policy.rank(graph, offeredTo(graph.state, graph.facts) { it.accepts(graph) })
            .map { c ->
                val back = source?.takeIf { com.point.core.flow.givesBackTheSource(c, graph, it) }
                bubbleOf(c, graph.state, reason ?: back?.let { com.point.core.flow.sourceIsHere(it) }, graph)
            }
    }

    /**
     * Исследования человеку не предлагаются- их выбирает Discovery, а не Planner (ADR-0001 §11).
     */
    private val offered: List<Capability> = capabilities.filterNot { it.meta.investigation }

    /**
     * Двери для этого объекта: применимые, не закрытые снаружи — и, если о содержимом
     * сказано, что читать в нём нечего, без дверей чтения (#994, #1101).
     */
    private fun offeredTo(
        state: ObjectState,
        facts: Map<String, String>,
        applies: (Capability) -> Boolean,
    ): List<Capability> = com.point.core.flow.offeredWhenUnfit(
        state,
        facts,
        offered.filter { applies(it) && blockerFor(it) == null },
    )

    private fun bubbleOf(
        c: Capability,
        state: ObjectState,
        unusableReason: String? = null,

        // Имя действия видит знание (#1010): «Понять» после успеха зовётся «Понять сильнее».
        graph: com.point.core.flow.GraphState? = null,
    ) = Bubble(
        icon = c.icon,
        title = graph?.let { c.label(it) } ?: c.label(state),
        capabilityId = c.id,
        expectedNextState = c.produces(state) ?: state,
        tier = tierOf(c.meta),
        intent = primaryIntentOf(c, state),

        yields = c.yields(state),
        unusableReason = unusableReason ?: offlineReason(c),
    )

    /** Про сеть спрашиваем один раз на список, а не у каждого действия. */
    private fun offlineReason(c: Capability): String? = when {
        !c.meta.network -> null
        !runCatching { network.isAvailable() }.getOrDefault(true) -> NO_INTERNET

        // Своё устройство — не «наружу»: «На компьютер» уносит объект на компьютер того же
        // человека, и режим приватности к нему не относится.
        c.meta.localOnly -> null
        else -> modeReason()
    }

    /**
     * Режим закрыл дорогу наружу — об этом говорит подпись, а не молчание (#943).
     *
     * Спрашивается не название режима, а то же правило, по которому цепочка и отказывает:
     * пускает ли этот режим наружу вообще. Когда у сервисов появятся собственные обещания,
     * подпись переменится вместе с ними и переписывать её не придётся.
     */
    private fun modeReason(): String? {
        val level = runCatching { privacy.level() }.getOrDefault(com.point.core.flow.PrivacyLevel.DEFAULT)
        val open = com.point.core.flow.anyoneAllowedAt(level)
        return if (open) null else com.point.core.flow.chainClosedBy(level)
    }

    private fun tierOf(meta: CapabilityMeta): BubbleTier = when {
        meta.network -> BubbleTier.AI
        meta.latency == Latency.INSTANT -> BubbleTier.INSTANT
        else -> BubbleTier.SMART
    }

    private fun primaryIntentOf(c: Capability, state: ObjectState): Intent =
        com.point.core.flow.primaryIntentOf(c, state)


    private fun missingFor(c: Capability, state: ObjectState): String? =
        if (c.accepts(state)) blockerFor(c) else c.missing(state)

    private fun blockerFor(c: Capability): String? = availability.blockerFor(c.id)

    override fun latentBubblesFor(state: ObjectState): List<LatentBubble> = hintsOf(state, offered)

    /**
     * Подсказка не переживает саму дверь (#1101): негодному объекту чтения не предлагаются —
     * значит и «разложите на страницы» у него не подсказывается. Одно и то же правило на
     * дверь и на подсказку, чтобы экран не отказывал строкой и не звал строкой ниже.
     */
    override fun latentBubblesFor(graph: com.point.core.flow.GraphState): List<LatentBubble> =
        hintsOf(graph.state, com.point.core.flow.offeredWhenUnfit(graph.state, graph.facts, offered))

    private fun hintsOf(state: ObjectState, from: List<Capability>): List<LatentBubble> {
        val hints = from.sortedBy { it.meta.priority }
            .mapNotNull { c -> missingFor(c, state)?.let { LatentBubble(c.icon, c.label(state), it) } }

        val byReason = hints.groupBy(LatentBubble::missing).values.toList()
        val rounds = byReason.maxOfOrNull { it.size } ?: 0
        return (0 until rounds)
            .flatMap { round -> byReason.mapNotNull { it.getOrNull(round) } }
            .take(MAX_LATENT)
    }

    override fun byId(id: CapabilityId): Capability =
        byIdMap[id] ?: error("No capability registered for id=${id.value}")

    private companion object {

        const val MAX_LATENT = 2

        /** Пока режим не подсказан снаружи: тестам и старым вызовам — прежнее поведение. */
        val OPEN_TO_EVERYONE = object : com.point.core.flow.CloudPrivacySettings {
            override fun level() = com.point.core.flow.PrivacyLevel.FREE_FIRST
            override suspend fun setLevel(level: com.point.core.flow.PrivacyLevel) = Unit
        }

        /** Одно слово про сеть на все экраны — оно живёт в `:core:flow` (#569, #759). */
        const val NO_INTERNET = com.point.core.flow.NO_INTERNET_NOTE
    }
}
