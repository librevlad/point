package com.point.data

import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.kindFor
import com.point.core.flow.EXTRACTED_KINDS
import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.ValueRef
import javax.inject.Inject

/**
 * Turns classified roles back into graph nodes (#222, шаг 6) — instant, no I/O, no network.
 *
 * The classifier writes `graph.role.*` into the object's metadata and stops there. Everything
 * that makes those facts *objects* happens here, in code: what kind of thing a `sender` is, what
 * relation it stands in, what id it gets. The model contributed a pointer at a line of text.
 *
 * **Why a separate enricher rather than the realizer building objects.** The same reason
 * [MetadataEntityEnricher] exists: metadata is journaled, objects are not. A flow restored after
 * process death — or an object re-opened from history — rebuilds the same graph, with the same
 * ids, without asking the model anything a second time. Paid work is done once.
 *
 * **One organisation, not one per role.** The id keys on the value, so a carrier that also issued
 * the document is a single node with two relations — which is what the graph should say.
 */
class GraphMetadataEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.INSTANT,
        mayYieldKinds = CLASSIFIER_ROLES.mapTo(mutableSetOf()) { it.kind } + KIND_PERSON,
    )

    override fun appliesTo(state: ObjectState) = true

    override suspend fun enrich(obj: PointObject): EnrichmentDelta {
        // A classified organisation is a leaf: nothing is classified out of it again.
        if (obj.state.kind in EXTRACTED_KINDS) return EnrichmentDelta()

        val objects = LinkedHashMap<String, PointObject>()
        val relations = mutableListOf<Relation>()
        val claims = CLASSIFIER_ROLES.mapNotNull { role ->
            obj.metadata[META_GRAPH_ROLE_PREFIX + role.key]?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { role to it }
        }
        // Вид унифицируется по значению (#297): «Нова Пошта» перевозчиком — организация, и тот
        // же текст отправителем не становится вторым узлом-«человеком». Узел один — вид один.
        val orgValues = claims
            .filter { (role, value) -> role.kindFor(value) != KIND_PERSON }
            .mapTo(mutableSetOf()) { normalized(it.second) }
        claims.forEach { (role, value) ->
            val kind = if (normalized(value) in orgValues) role.kind else role.kindFor(value)
            val id = nodeId(obj.id, value)
            objects.getOrPut(id) {
                PointObject(
                    id = id,
                    mime = "text/plain",
                    uri = ValueRef(value), // the value IS the content — no file behind it
                    state = ObjectState(kind),
                    // A model's reading, not a rule's: the graph carries that honestly.
                    confidence = CLASSIFIED_CONFIDENCE,
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

        /** A model pointed at a line; nobody checked it against the world. Below a rule's
         *  certainty on purpose, so the screen can say «возможно» and mean it. */
        const val CLASSIFIED_CONFIDENCE = 0.7f

        /** Deterministic and value-keyed: re-enrichment does not double the graph, and the
         *  same party in two roles is one node — вид в id не входит (#297), иначе человек-и-
         *  организация с одним именем раздваивались бы. */
        fun nodeId(sourceId: String, value: String) = "$sourceId:party:${normalized(value)}"

        fun normalized(value: String) = value.lowercase().replace(Regex("""\s+"""), " ").trim()
    }
}
