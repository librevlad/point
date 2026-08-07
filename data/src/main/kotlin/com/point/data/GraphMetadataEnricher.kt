package com.point.data

import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.kindFor
import com.point.core.flow.EXTRACTED_KINDS
import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.provenanceOf
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.Relation
import com.point.core.model.ValueRef
import javax.inject.Inject

class GraphMetadataEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.INSTANT,
        mayYieldKinds = CLASSIFIER_ROLES.mapTo(mutableSetOf()) { it.kind } + KIND_PERSON,
    )

    override fun appliesTo(state: ObjectState) = true

    override suspend fun enrich(obj: PointObject): EnrichmentDelta {

        if (obj.state.kind in EXTRACTED_KINDS) return EnrichmentDelta()

        val objects = LinkedHashMap<String, PointObject>()
        val relations = mutableListOf<Relation>()
        val claims = CLASSIFIER_ROLES.mapNotNull { role ->
            obj.metadata[META_GRAPH_ROLE_PREFIX + role.key]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { role to it }
        }

        val orgValues = claims
            .filter { (role, value) -> role.kindFor(value) != KIND_PERSON }
            .mapTo(mutableSetOf()) { normalized(it.second) }
        claims.forEach { (role, value) ->
            val kind = if (normalized(value) in orgValues) role.kind else role.kindFor(value)
            val id = nodeId(obj.id, value)
            val roleKey = META_GRAPH_ROLE_PREFIX + role.key
            objects.getOrPut(id) {
                PointObject(
                    id = id,
                    mime = "text/plain",
                    uri = ValueRef(value),
                    state = ObjectState(kind),

                    metadata = roleSlice(obj.metadata, roleKey, value),
                    provenance = roleProvenance(obj.metadata, roleKey),
                    sourceObjects = listOf(obj.id),
                    creatorAction = CREATOR,
                )
            }
            relations += Relation(id, role.relation, obj.id)
        }
        if (objects.isEmpty()) return EnrichmentDelta()
        return EnrichmentDelta(objects = objects.values.toList(), relations = relations)
    }

    private companion object {
        const val CREATOR = "classifier"

        fun roleProvenance(metadata: Map<String, String>, roleKey: String): Provenance =
            provenanceOf(metadata, roleKey).takeIf { it != Provenance.GIVEN } ?: Provenance.MODEL

        fun roleSlice(metadata: Map<String, String>, roleKey: String, value: String) = buildMap {
            put(roleKey, value)
            put(roleKey + META_SOURCE_SUFFIX, roleProvenance(metadata, roleKey).wire)
            metadata[roleKey + META_EVIDENCE_SUFFIX]?.let { put(roleKey + META_EVIDENCE_SUFFIX, it) }

            metadata[roleKey + META_ALT_SUFFIX]?.let { put(roleKey + META_ALT_SUFFIX, it) }
        }

        fun nodeId(sourceId: String, value: String) = "$sourceId:party:${normalized(value)}"

        fun normalized(value: String) = value.lowercase().replace(Regex("""\s+"""), " ").trim()
    }
}
