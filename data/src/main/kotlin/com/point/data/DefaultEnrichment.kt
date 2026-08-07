package com.point.data

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.Enrichment
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.EnrichmentUpdate
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

class DefaultEnrichment @Inject constructor(
    private val enrichers: Set<@JvmSuppressWildcards Enricher>,
    private val registry: CapabilityRegistry,
) : Enrichment {

    override fun enrich(obj: PointObject): Flow<EnrichmentUpdate> = flow {
        val found = mutableSetOf<Feature>()
        val metadata = mutableMapOf<String, String>()
        val objects = mutableListOf<PointObject>()
        val relations = mutableListOf<Relation>()

        val waves = enrichers.filter { it.appliesTo(obj.state) }
            .groupBy { it.meta.cost }.entries.sortedBy { it.key }
        for ((cost, wave) in waves) {
            val soFar = found.fold(obj.state) { s, f -> s.with(f) }
            val toRun =
                if (cost == EnrichCost.SLOW) wave.filter { worthRunning(soFar, objects, it.meta) }
                else wave
            if (toRun.isEmpty()) continue

            coroutineScope {
                val results = Channel<Pair<Enricher, EnrichmentDelta>>(Channel.UNLIMITED)
                for (enricher in toRun) launch {
                    val delta = runCatching { enricher.enrich(obj) }.getOrDefault(EnrichmentDelta())
                    results.send(enricher to delta)
                }
                val running = toRun.toMutableList()
                emit(snapshot(found, metadata, objects, relations, running))
                repeat(toRun.size) {
                    val (enricher, delta) = results.receive()
                    found += delta.features
                    metadata += delta.metadata
                    objects += delta.objects
                    relations += delta.relations
                    running -= enricher
                    emit(snapshot(found, metadata, objects, relations, running))
                }
            }
        }
        emit(
            EnrichmentUpdate(
                found.toSet(), metadata.toMap(), emptyList(), objects.toList(), relations.toList(),
            ),
        )
    }

    private fun snapshot(
        features: Set<Feature>,
        metadata: Map<String, String>,
        objects: List<PointObject>,
        relations: List<Relation>,
        running: List<Enricher>,
    ) = EnrichmentUpdate(
        features.toSet(),
        metadata.toMap(),
        running.mapNotNull { it.meta.label },
        objects.toList(),
        relations.toList(),
    )

    private fun worthRunning(
        state: ObjectState,
        found: List<PointObject>,
        meta: EnricherMeta,
    ): Boolean {

        if (meta.mayYield.isEmpty() && meta.mayYieldKinds.isEmpty()) return true
        return opensNewActions(state, meta.mayYield) || yieldsNewObjects(found, meta.mayYieldKinds)
    }

    private fun opensNewActions(state: ObjectState, mayYield: Set<Feature>): Boolean {
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
}
