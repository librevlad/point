package com.point.data

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.Enrichment
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.EnrichmentUpdate
import com.point.core.model.Feature
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The progressive enrichment scheduler. Applicable enrichers run in cost waves
 * (INSTANT → FAST → SLOW); inside a wave they run concurrently and every completion
 * emits an update — so cheap findings reach the screen while expensive ones still work.
 *
 * A SLOW enricher runs only if the features it [com.point.core.flow.EnricherMeta.mayYield]
 * could open at least one new action on the state as enriched by the cheaper waves —
 * expensive knowledge is bought only when it can change what the user sees.
 */
class DefaultEnrichment @Inject constructor(
    private val enrichers: Set<@JvmSuppressWildcards Enricher>,
    private val registry: CapabilityRegistry,
) : Enrichment {

    override fun enrich(obj: PointObject): Flow<EnrichmentUpdate> = flow {
        val found = mutableSetOf<Feature>()
        val metadata = mutableMapOf<String, String>()

        val waves = enrichers.filter { it.appliesTo(obj.state) }
            .groupBy { it.meta.cost }.entries.sortedBy { it.key }
        for ((cost, wave) in waves) {
            val soFar = found.fold(obj.state) { s, f -> s.with(f) }
            val toRun =
                if (cost == EnrichCost.SLOW) wave.filter { opensNewActions(soFar, it.meta.mayYield) }
                else wave
            if (toRun.isEmpty()) continue

            coroutineScope {
                val results = Channel<Pair<Enricher, EnrichmentDelta>>(Channel.UNLIMITED)
                for (enricher in toRun) launch {
                    val delta = runCatching { enricher.enrich(obj) }.getOrDefault(EnrichmentDelta())
                    results.send(enricher to delta)
                }
                val running = toRun.toMutableList()
                emit(snapshot(found, metadata, running))
                repeat(toRun.size) {
                    val (enricher, delta) = results.receive()
                    found += delta.features
                    metadata += delta.metadata
                    running -= enricher
                    emit(snapshot(found, metadata, running))
                }
            }
        }
        emit(EnrichmentUpdate(found.toSet(), metadata.toMap(), emptyList()))
    }

    private fun snapshot(
        features: Set<Feature>,
        metadata: Map<String, String>,
        running: List<Enricher>,
    ) = EnrichmentUpdate(features.toSet(), metadata.toMap(), running.mapNotNull { it.meta.label })

    private fun opensNewActions(state: ObjectState, mayYield: Set<Feature>): Boolean {
        if (mayYield.isEmpty()) return true // unknown yield — run rather than guess
        val current = registry.bubblesFor(state).mapTo(mutableSetOf()) { it.capabilityId }
        val speculative = mayYield.fold(state) { s, f -> s.with(f) }
        return registry.bubblesFor(speculative).any { it.capabilityId !in current }
    }
}
