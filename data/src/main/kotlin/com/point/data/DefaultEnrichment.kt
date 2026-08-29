package com.point.data

import com.point.core.flow.ANSWERED_STATES
import com.point.core.flow.AwaitingInvestigation
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Enrichment
import com.point.core.flow.EnrichmentUpdate
import com.point.core.flow.FailedInvestigation
import com.point.core.flow.GraphState
import com.point.core.flow.InvestigationState
import com.point.core.flow.Latency
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.focusOf
import com.point.core.flow.Resolver
import com.point.core.flow.cloudScopeOf
import com.point.core.flow.investigationOutcome
import com.point.core.flow.investigationStateOf
import com.point.core.flow.knownBy
import com.point.core.flow.mergeKnowledge
import com.point.core.flow.withInvestigation
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.Relation
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Цикл Progressive Understanding (RFC §11) поверх обычного механизма действий.
 *
 * Своего реестра и своего пути исполнения у исследований нет: кандидаты берутся из общего
 * `CapabilityRegistry`, исполнитель — у `Resolver`, результат приходит обычным `ActionResult`
 * (ADR-0001 §11, §18).
 */
class DefaultEnrichment @Inject constructor(
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val consent: PrivacyConsent,

    // Страна для разбора номеров — вход, а не состояние мира (#1129).
    private val region: com.point.core.flow.PhoneRegion,
) : Enrichment {

    override fun enrich(
        obj: PointObject,
        answeredElsewhere: Flow<CapabilityId>,
    ): Flow<EnrichmentUpdate> = flow {
        val found = mutableSetOf<Feature>()
        var metadata: Map<String, String> = emptyMap()
        val objects = mutableListOf<PointObject>()
        val relations = mutableListOf<Relation>()
        val failed = mutableListOf<FailedInvestigation>()
        val awaiting = mutableListOf<AwaitingInvestigation>()
        val answered = LinkedHashMap<CapabilityId, Answered>()

        val investigations = registry.all().filter { it.meta.investigation }

        // Контекст запуска захватывается здесь и не перечитывается при завершении:
        // поздний результат остаётся знанием своей области, а не текущего Focus.
        val focus = focusOf(obj.metadata, obj.id)

        val candidates = if (focus == null) {
            investigations.filter { it.accepts(GraphState(obj)) }
        } else {

            // Focused-проход- только исследования, ставшие применимыми из-за Focus.
            // Остальные Focus не перезапускает: их ответ от области не зависит.
            val focused = GraphState(obj, focus = focus)
            val bare = GraphState(obj)
            investigations.filter { it.accepts(focused) && !it.accepts(bare) }
        }

        val waves = candidates.groupBy { it.meta.latency }.entries.sortedBy { it.key }

        for ((latency, wave) in waves) {
            val soFar = found.fold(obj.state) { s, f -> s.with(f) }
            val toRun =
                if (latency == Latency.SLOW) {
                    wave.filter {

                        investigationStateOf(obj.metadata, it.id, focus) !in ANSWERED_STATES &&
                            worthRunning(soFar, objects, it)
                    }
                } else {
                    wave
                }.filter { allowedHere(it) }
            if (toRun.isEmpty()) continue

            coroutineScope {
                val results = Channel<Pair<Capability, Result<ActionResult?>>>(Channel.UNLIMITED)
                for (investigation in toRun) launch {

                    // Отмена на уровне «объект + вопрос» (#1242): исследование прерывается,
                    // когда на его вопрос уже ответили мимо прохода. Прерывается ровно оно —
                    // соседние вопросы этой же волны идут дальше, и их поздний результат
                    // остаётся знанием объекта.
                    //
                    // Вопрос под областью — другой вопрос («что здесь», а не «что в
                    // объекте»), и закрывают его отдельно: focused-проход отмену не слушает.
                    val stop = if (focus == null) answeredElsewhere else emptyFlow()
                    results.send(investigation to runCatching { until(stop, investigation.id) { run(investigation, obj) } })
                }
                val running = toRun.toMutableList()
                emit(snapshot(found, metadata, objects, relations, running, failed, awaiting))
                repeat(toRun.size) {
                    val (investigation, outcome) = results.receive()
                    val label = investigation.label(obj.state).takeIf { it.isNotBlank() }

                    when (val result = outcome.getOrNull()) {

                        is ActionResult.Done -> {
                            val findings = result.findings ?: Findings()
                            val bad = unusable(findings)
                            if (bad != null) {

                                failed += FailedInvestigation(investigation.id, label, bad)
                            } else {
                                found += findings.features
                                objects += findings.objects
                                relations += findings.relations
                                metadata = mergeKnowledge(metadata, findings.metadata, region = region.code())
                                answered[investigation.id] = Answered(
                                    keys = findings.metadata.keys,
                                    fruitful = findings.features.isNotEmpty() ||
                                        findings.objects.isNotEmpty() || findings.relations.isNotEmpty(),
                                )
                                metadata = statesOf(metadata, answered, focus)
                            }
                        }

                        is ActionResult.NeedsInput ->
                            awaiting += AwaitingInvestigation(investigation.id, label, result.prompt)

                        is ActionResult.NeedsImage ->
                            awaiting += AwaitingInvestigation(
                                investigation.id, label, result.prompt, needsImage = true,
                            )

                        is ActionResult.Failure ->
                            failed += FailedInvestigation(investigation.id, label, result.reason)

                        is ActionResult.Success ->
                            failed += FailedInvestigation(investigation.id, label, WRONG_SHAPE)

                        // Исключение мимо `investigated` — та же граница слов (#1225):
                        // человеку выходит слово слоя, если он его объявил, а чужой текст
                        // остаётся на своём слое.
                        //
                        // Прерванное по чужому ответу исключения не приносит и упрёком не
                        // становится (#1242): это не срыв, а закрытый другим путём вопрос —
                        // знание о нём уже пришло, и говорить человеку не о чем.
                        null -> outcome.exceptionOrNull()?.let { thrown ->
                            failed += FailedInvestigation(
                                investigation.id,
                                label,
                                com.point.core.flow.ownWordsOf(thrown) ?: com.point.core.flow.INVESTIGATION_FAILED,
                            )
                        }
                    }
                    running -= investigation
                    emit(snapshot(found, metadata, objects, relations, running, failed, awaiting))
                }
            }
        }
        emit(
            EnrichmentUpdate(
                found.toSet(), metadata, emptyList(), objects.toList(), relations.toList(),
                failed.toList(), awaiting.toList(),
            ),
        )
    }

    /**
     * Работа до своего ответа: `null`, если ответ на этот вопрос пришёл раньше со стороны
     * (#1242).
     *
     * Отменяется только сама работа — тот, кто её вёл, остаётся жив и докладывает, что его
     * больше не ждут. Чужая отмена (человек ушёл с объекта, кончился проход) сюда не
     * заворачивается: она идёт дальше как была.
     */
    private suspend fun <T> until(
        answered: Flow<CapabilityId>,
        question: CapabilityId,
        work: suspend () -> T,
    ): T? = coroutineScope {
        val running = async { work() }

        // Ждём именно свой вопрос — и переживаем то, что его так и не назвали: у прохода без
        // сторонних ответов поток пуст и кончается сразу, и «не дождались» здесь нормальный
        // исход, а не срыв исследования.
        val watch = launch { answered.firstOrNull { it == question }?.let { running.cancel() } }
        try {
            running.await()
        } catch (stopped: kotlinx.coroutines.CancellationException) {
            coroutineContext.ensureActive()
            null
        } finally {
            watch.cancel()
        }
    }

    /**
     * Кто именно исследовал — часть добытого знания (#1127).
     *
     * Исполнителя выбирает Resolver, и знать его имя может только этот шов: само исследование
     * видит свой вопрос, а не то, кем он был решён в этот раз.
     */
    private suspend fun run(investigation: Capability, obj: PointObject): ActionResult {
        val realizer = resolver.realizerFor(investigation.id, obj.state)
        return realizer.perform(obj, null).knownBy(obj, realizer.meta.actor)
    }

    /**
     * ADR-0001 §19: автоматическое исследование не пересекает внешнюю границу без заранее
     * данного согласия. Молча наружу не ходим даже ради лучшего результата.
     */
    private suspend fun allowedHere(investigation: Capability): Boolean =
        !runCatching { resolver.leavesDevice(investigation.id) }.getOrDefault(false) ||
            runCatching { consent.allowed(cloudScopeOf(investigation.id)) }.getOrDefault(false)

    /**
     * Validation (ADR-0001 §15): годится ли результат для Graph. Противоречия она не разрешает
     * и конкурирующие прочтения не выбрасывает — этим занимается merge.
     */
    private fun unusable(findings: Findings): String? {
        if (findings.objects.any { it.id.isBlank() }) return "объект без идентичности"
        if (findings.objects.map { it.id }.toSet().size != findings.objects.size) {
            return "объекты с одинаковой идентичностью"
        }
        if (findings.relations.any { it.fromId.isBlank() || it.toId.isBlank() }) {
            return "связь в никуда"
        }
        return null
    }

    private data class Answered(val keys: Set<String>, val fruitful: Boolean)

    /**
     * Состояние знания по завершившимся исследованиям (ADR-0001 §9).
     *
     * Пересчитывается целиком после каждого merge- расхождение появляется только тогда, когда
     * второй источник уже прочитан, и обязано быть видно у обоих исследований, а не у последнего.
     *
     * Сорвавшееся исследование сюда не попадает: у него нет исхода знания.
     */
    private fun statesOf(
        metadata: Map<String, String>,
        answered: Map<CapabilityId, Answered>,
        focus: com.point.core.flow.Focus?,
    ): Map<String, String> = answered.entries.fold(metadata) { acc, (id, told) ->
        val state = if (told.keys.isEmpty() && told.fruitful) {
            InvestigationState.FOUND
        } else {
            investigationOutcome(acc, told.keys)
        }

        withInvestigation(acc, id, state, focus)
    }

    private fun snapshot(
        features: Set<Feature>,
        metadata: Map<String, String>,
        objects: List<PointObject>,
        relations: List<Relation>,
        running: List<Capability>,
        failed: List<FailedInvestigation>,
        awaiting: List<AwaitingInvestigation>,
    ) = EnrichmentUpdate(
        features.toSet(),
        metadata,
        running.mapNotNull { it.label(ANY).takeIf(String::isNotBlank) },
        objects.toList(),
        relations.toList(),
        failed.toList(),
        awaiting.toList(),
    )

    private fun worthRunning(
        state: com.point.core.model.ObjectState,
        found: List<PointObject>,
        investigation: Capability,
    ): Boolean {
        val meta = investigation.meta
        if (meta.mayYield.isEmpty() && meta.mayYieldKinds.isEmpty()) return true
        return opensNewActions(state, meta.mayYield) || yieldsNewObjects(found, meta.mayYieldKinds)
    }

    private fun opensNewActions(state: com.point.core.model.ObjectState, mayYield: Set<Feature>): Boolean {
        if (mayYield.isEmpty()) return false
        val current = registry.bubblesFor(state).mapTo(mutableSetOf()) { it.capabilityId }
        val speculative = mayYield.fold(state) { s, f -> s.with(f) }
        return registry.bubblesFor(speculative).any { it.capabilityId !in current }
    }

    private fun yieldsNewObjects(found: List<PointObject>, mayYieldKinds: Set<ObjectKind>): Boolean {
        if (mayYieldKinds.isEmpty()) return false
        val have = found.mapTo(mutableSetOf()) { it.state.kind }
        return mayYieldKinds.any { it !in have }
    }

    private companion object {

        const val WRONG_SHAPE = "исследование вернуло объект вместо знания"

        val ANY = com.point.core.model.ObjectState(ObjectKind.UNKNOWN)
    }
}
