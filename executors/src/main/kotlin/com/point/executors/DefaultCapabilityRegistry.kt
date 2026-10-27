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

    /**
     * Кого позовут первым (#1413). Обещание, называющее здешнего исполнителя («· на
     * телефоне»), говорит правду только о нём: в режиме «делай лучшее» слитое с компьютером
     * действие уходило на компьютер и в сервис под словами телефона.
     */
    private val chosen: com.point.core.flow.ChosenExecutor = com.point.core.flow.ChosenExecutor { _, _ -> null },
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
    override fun bubblesFor(state: ObjectState): List<Bubble> {
        val online = onlineOnce()
        return policy.rank(state, offered.filter { it.accepts(state) && blockerFor(it) == null })
            .map { c -> bubbleOf(c, state, online = online) }
    }

    override fun bubblesFor(graph: com.point.core.flow.GraphState): List<Bubble> {
        val reason = graph.unusableReason()

        // Действие, возвращающее исходник, из которого объект получен, не прячется, а уходит
        // вниз и говорит об этом (#925).
        val source = com.point.core.flow.inverseSourceKind(graph)
        val online = onlineOnce()
        return policy.rank(graph, offeredTo(graph.state, graph.facts) { it.accepts(graph) })
            .map { c ->
                val back = source?.takeIf { com.point.core.flow.givesBackTheSource(c, graph, it) }
                bubbleOf(c, graph.state, reason ?: back?.let { com.point.core.flow.sourceIsHere(it) }, graph, online)
            }
    }

    /**
     * Есть ли сеть — один ответ на весь список (#539). Вопрос системе не бесплатен: на A34 он,
     * заданный каждым сетевым действием при каждом пересчёте, занимал заметную долю главного
     * потока, пока кольцо «Обрабатываю…» стояло. И спрашивается только тогда, когда в списке
     * есть сетевое действие.
     */
    private fun onlineOnce(): Lazy<Boolean> =
        lazy(LazyThreadSafetyMode.NONE) { runCatching { network.isAvailable() }.getOrDefault(true) }

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
        online: Lazy<Boolean> = onlineOnce(),
    ) = Bubble(
        icon = c.icon,
        title = graph?.let { c.label(it) } ?: c.label(state),
        capabilityId = c.id,
        expectedNextState = c.produces(state) ?: state,
        tier = tierOf(c.meta),
        intent = primaryIntentOf(c, state),

        yields = c.yields(state).let { promise ->
            if (!com.point.core.flow.promiseNamesHere(promise)) promise
            else com.point.core.flow.promiseOfChosen(promise, runCatching { chosen.chosenFor(c.id, state) }.getOrNull())
        },
        unusableReason = unusableReason ?: offlineReason(c, online),

        // Какое найденное значение действие продолжает — по нему экран кладёт действие к
        // значению, а не в группу вида функции.
        because = graph?.let { com.point.core.flow.becauseOf(c, it) },
    )

    /** Про сеть спрашиваем один раз на список, а не у каждого действия. */
    private fun offlineReason(c: Capability, online: Lazy<Boolean>): String? = when {
        !c.meta.network -> null
        !online.value -> NO_INTERNET

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
        val NO_INTERNET: String get() = com.point.core.flow.NO_INTERNET_NOTE
    }
}
