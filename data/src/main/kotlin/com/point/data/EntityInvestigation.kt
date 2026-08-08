package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.META_ENTITY_ADDRESS
import com.point.core.flow.addressFacts
import com.point.core.flow.expandAddressToLine
import com.point.core.flow.alternativesOf
import com.point.core.flow.altValue
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.provenanceOf
import com.point.core.flow.EXTRACTED_KINDS
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import com.point.core.flow.EntityExtractor
import com.point.core.flow.asFeature
import com.point.core.flow.asMetaKey
import com.point.core.flow.isBareClock
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class EntityInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
        mayYield = setOf(
            Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS, Feature.HAS_DATE, Feature.HAS_CARD,
        ),
        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT

    /**
     * Под Focus сущности извлекаются и из области изображения — по уже прочитанному слою
     * атомов (ADR-0001 §10- Focus поднимает приоритет указанной области). Новых движков
     * это не требует: слой уже лежит в `ocr.atoms.ref`.
     */
    override fun accepts(graph: com.point.core.flow.GraphState) =
        accepts(graph.state) ||
            (
                graph.state.kind == ObjectKind.IMAGE &&
                    graph.focus != null &&
                    graph.fact(com.point.core.flow.META_OCR_ATOMS_REF) != null
                )

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("entities")
    }
}

class EntityInvestigationRealizer @Inject constructor(
    private val extractor: EntityExtractor,
) : Realizer {

    override val capabilityId = EntityInvestigation.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching { findings(input) }.fold(
            onSuccess = { ActionResult.Done("", it) },

            onFailure = { ActionResult.Failure(it.message ?: FAILED, recoverable = true) },
        )

    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {
        val focus = com.point.core.flow.focusOf(obj.metadata, obj.id)
        val atomsRef = obj.metadata[com.point.core.flow.META_OCR_ATOMS_REF]
        if (focus != null && atomsRef != null && obj.state.kind == ObjectKind.IMAGE) {
            return@withContext focusedFindings(obj, focus, atomsRef)
        }
        val file = File(obj.uri.value)

        if (!file.isFile) error(NO_PAYLOAD)
        val text = file.readText().take(MAX_CHARS)
        if (text.isBlank()) return@withContext Findings()
        entityDelta(obj, extractor.extract(text), text)
    }

    /**
     * Сущности из указанной области: атомы по `focus.atomIds`, иначе — пересекающие регион.
     * Слой не перечитывается с картинки, движки регионов не получают (ADR-0001 §10).
     */
    private suspend fun focusedFindings(
        obj: PointObject,
        focus: com.point.core.flow.Focus,
        atomsRef: String,
    ): Findings {
        val layer = com.point.core.flow.AtomCodec.decode(File(atomsRef).readText())
        val wanted = focus.atomIds.toSet()
        val region = focus.region
        val chosen = when {
            wanted.isNotEmpty() -> layer.atoms.filter { it.id in wanted }
            region != null -> layer.atoms.filter { it.box.intersects(region) }
            else -> emptyList()
        }
        if (chosen.isEmpty()) return Findings()
        val text = chosen
            .sortedWith(compareBy({ it.box.centerY }, { it.box.left }))
            .joinToString(" ") { it.text }
        if (text.isBlank()) return Findings()

        val at = com.point.core.flow.regionWire(
            region ?: chosen.map { it.box }.reduce { a, b -> a.union(b) },
        )
        return focusedDelta(obj, extractor.extract(text), at)
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}

/**
 * Находки области. Отличия от полного прохода:
 *
 * - идентичность различает значения (`source:phone:<значение>`) — по прецеденту identifiers:
 *   два телефона в двух местах остаются двумя объектами;
 * - другое значение уже занятого факта уходит в `.more` (существующая конвенция «ещё значения
 *   того же вида»), а не в `.alt`: два настоящих телефона — не конфликт прочтений одного;
 * - каждый объект несёт `at.region` своей области.
 */
internal fun focusedDelta(
    source: PointObject,
    entities: List<com.point.core.flow.Entity>,
    at: String,
): Findings {
    if (source.state.kind in EXTRACTED_KINDS) return Findings()
    val features = entities.mapNotNullTo(mutableSetOf()) { it.type.asFeature() }

    val facts = LinkedHashMap<String, String>()
    val more = LinkedHashMap<String, MutableList<String>>()
    val objects = LinkedHashMap<String, PointObject>()

    entities.sortedBy { it.isBareClock() }.forEach { e ->
        val key = e.type.asMetaKey() ?: return@forEach
        val suffix = key.removePrefix(META_ENTITY_PREFIX)
        val (kind, feature) = ENTITY_KINDS[suffix] ?: return@forEach
        val value = e.value.trim().takeIf { it.isNotBlank() } ?: return@forEach

        val known = source.metadata[key]
        val sameAsKnown = known != null && com.point.core.flow.normConsensus(known) ==
            com.point.core.flow.normConsensus(value)
        when {
            known.isNullOrBlank() && key !in facts -> facts[key] = value
            sameAsKnown || com.point.core.flow.normConsensus(facts[key].orEmpty()) ==
                com.point.core.flow.normConsensus(value) -> Unit

            else -> more.getOrPut(key) { mutableListOf() } += value
        }

        if (sameAsKnown) return@forEach
        val id = "${source.id}:$suffix:${value.filter(Char::isLetterOrDigit).uppercase()}"
        objects.getOrPut(id) {
            PointObject(
                id = id,
                mime = "text/plain",
                uri = ValueRef(value),
                state = ObjectState(kind, setOf(feature)),
                metadata = mapOf(key to value, com.point.core.flow.META_AT_REGION to at),
                sourceObjects = listOf(source.id),
                creatorAction = ENTITY_CREATOR,
            )
        }
    }
    more.forEach { (key, values) -> facts[key + com.point.core.flow.META_MORE_SUFFIX] = altValue(values.distinct()) }

    return Findings(
        features = features,
        metadata = facts,
        objects = objects.values.toList(),
        relations = objects.values.map { Relation(it.id, RelationType.FOUND_IN, source.id) },
    )
}

internal fun entityDelta(
    source: PointObject,
    entities: List<com.point.core.flow.Entity>,
    text: String = "",
): Findings {
    val features = entities.mapNotNullTo(mutableSetOf()) { it.type.asFeature() }
    val extracted = buildMap {

        entities.sortedBy { it.isBareClock() }.forEach { e ->
            e.type.asMetaKey()?.let { key ->

                val value = if (e.type == com.point.core.flow.EntityType.ADDRESS && text.isNotEmpty()) {
                    expandAddressToLine(e.value, text)
                } else {
                    e.value
                }
                putIfAbsent(key, value)
            }
        }
    }

    val ruled = if (META_ENTITY_ADDRESS in extracted) emptyMap() else addressFacts(text)
    val facts = extracted + ruled

    if (ruled.isNotEmpty()) features += Feature.HAS_ADDRESS
    val (objects, relations) = entityObjects(source, facts, creator = ENTITY_CREATOR)
    return Findings(features, facts, objects, relations)
}

internal const val ENTITY_CREATOR = "entity-enricher"

internal fun entityObjects(
    source: PointObject,
    facts: Map<String, String>,
    creator: String,
): Pair<List<PointObject>, List<Relation>> {

    if (source.state.kind in EXTRACTED_KINDS) return emptyList<PointObject>() to emptyList()

    val objects = ENTITY_KINDS.mapNotNull { (suffix, kindAndFeature) ->
        val key = META_ENTITY_PREFIX + suffix
        val value = facts[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val (kind, feature) = kindAndFeature

        val alternatives = alternativesOf(facts, key)

        val slice = buildMap {
            put(key, value)
            if (alternatives.isNotEmpty()) {
                put(key + META_ALT_SUFFIX, altValue(alternatives))
            }

            facts[key + META_EVIDENCE_SUFFIX]?.let { put(key + META_EVIDENCE_SUFFIX, it) }
            facts[key + META_SOURCE_SUFFIX]?.let { put(key + META_SOURCE_SUFFIX, it) }
        }
        PointObject(
            id = "${source.id}:$suffix",
            mime = "text/plain",
            uri = ValueRef(value),
            state = ObjectState(kind, setOf(feature)),
            metadata = slice,
            provenance = provenanceOf(slice, key),
            sourceObjects = listOf(source.id),
            creatorAction = creator,
        )
    }
    return objects to objects.map { Relation(it.id, RelationType.FOUND_IN, source.id) }
}

private val ENTITY_KINDS: Map<String, Pair<ObjectKind, Feature>> = mapOf(
    "phone" to (KIND_PHONE to Feature.HAS_PHONE),
    "email" to (KIND_EMAIL to Feature.HAS_EMAIL),
    "url" to (KIND_URL to Feature.HAS_URL),
    "address" to (KIND_ADDRESS to Feature.HAS_ADDRESS),
    "date" to (KIND_DATE to Feature.HAS_DATE),
)

private const val FAILED = "исследование не удалось"

private const val NO_PAYLOAD = "текст объекта недоступен"
