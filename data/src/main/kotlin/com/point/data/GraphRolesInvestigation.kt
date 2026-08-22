package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.kindFor
import com.point.core.flow.EXTRACTED_KINDS
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

class GraphRolesInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.INSTANT,
        mayYieldKinds = CLASSIFIER_ROLES.mapTo(mutableSetOf()) { it.kind } + KIND_PERSON,
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = true

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("graph-roles")
    }
}

class GraphRolesInvestigationRealizer @Inject constructor() : Realizer {

    override val capabilityId = GraphRolesInvestigation.ID

    override val meta = com.point.core.flow.RealizerMeta(actor = "role-rules")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings {

        if (obj.state.kind in EXTRACTED_KINDS) return Findings()

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
        if (objects.isEmpty()) return Findings()
        return Findings(objects = objects.values.toList(), relations = relations)
    }

    private companion object {
        const val CREATOR = "classifier"

        /**
         * Роль назвала модель — так и записываем.
         *
         * Правило опиралось на молчаливое умолчание: «если происхождение не GIVEN, взять его,
         * иначе MODEL». Пока отсутствие `.src` означало GIVEN, это работало случайно; с
         * появлением отдельного «неизвестно» (#948) стало видно, что никто здесь про
         * происхождение и не говорил. Роли выводит классификатор из ответа модели — это MODEL,
         * и только правка человеком сильнее.
         */
        fun roleProvenance(metadata: Map<String, String>, roleKey: String): Provenance =
            provenanceOf(metadata, roleKey)
                .takeIf { it == Provenance.HUMAN }
                ?: Provenance.MODEL

        fun roleSlice(metadata: Map<String, String>, roleKey: String, value: String) = buildMap {
            put(roleKey, value)
            put(roleKey + META_SOURCE_SUFFIX, roleProvenance(metadata, roleKey).wire)
            metadata[roleKey + META_EVIDENCE_SUFFIX]?.let { put(roleKey + META_EVIDENCE_SUFFIX, it) }

            metadata[roleKey + META_ALT_SUFFIX]?.let { put(roleKey + META_ALT_SUFFIX, it) }
        }

        // Идентичность стороны одна на всех (#1176): формула живёт в core/flow, а не
        // списывается здесь ещё раз.
        fun nodeId(sourceId: String, value: String) = com.point.core.flow.partyNodeId(sourceId, value)

        fun normalized(value: String) = com.point.core.flow.normalizedParty(value)
    }
}

