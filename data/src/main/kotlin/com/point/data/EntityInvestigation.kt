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
import com.point.core.flow.moreOf
import com.point.core.flow.normConsensus
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_MORE_SUFFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_LINE_SUFFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.provenanceOf
import com.point.core.flow.EXTRACTED_KINDS
import com.point.core.flow.ENTITY_CREATOR
import com.point.core.flow.ENTITY_KINDS
import com.point.core.flow.bareTimestamp
import com.point.core.flow.entityDelta
import com.point.core.flow.entityObjects
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

        val ID = com.point.core.flow.KnownCapabilities.ENTITIES
    }
}

class EntityInvestigationRealizer(
    private val extractor: EntityExtractor,

    // Тестовый планировщик видит IO-работу целиком: хвост на реальном пуле доживал
    // после конца теста и ронял соседний (UncaughtExceptionsBeforeTest, 2026-08-09).
    private val io: kotlin.coroutines.CoroutineContext,
) : Realizer {

    @Inject constructor(extractor: EntityExtractor) : this(extractor, Dispatchers.IO)

    override val capabilityId = EntityInvestigation.ID

    override val meta = com.point.core.flow.RealizerMeta(actor = "entities")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings = withContext(io) {
        val focus = com.point.core.flow.focusOf(obj.metadata, obj.id)
        val atomsRef = obj.metadata[com.point.core.flow.META_OCR_ATOMS_REF]
        if (focus != null && atomsRef != null && obj.state.kind == ObjectKind.IMAGE) {
            return@withContext focusedFindings(obj, focus, atomsRef)
        }
        val file = File(obj.uri.value)

        if (!file.isFile) error(com.point.core.flow.NO_TEXT_PAYLOAD)
        val text = file.readText().take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)
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

    /** Происхождение знания области — то же правило, что и у полного прохода (#990). */
    source_: com.point.core.model.Provenance = source.provenance,
): Findings {
    if (source.state.kind in EXTRACTED_KINDS) return Findings()

    // «Голое время это никогда не дата, это мусор» (#651): и признака не даёт.
    val meaningful = entities.filterNot {
        it.type == com.point.core.flow.EntityType.DATE_TIME && it.isBareClock()
    }
    val features = meaningful.mapNotNullTo(mutableSetOf()) { it.type.asFeature() }

    val facts = LinkedHashMap<String, String>()
    val more = LinkedHashMap<String, MutableList<String>>()
    val objects = LinkedHashMap<String, PointObject>()

    meaningful.sortedBy { it.isBareClock() }.forEach { e ->
        val key = e.type.asMetaKey() ?: return@forEach
        val suffix = key.removePrefix(META_ENTITY_PREFIX)
        val (kind, feature) = ENTITY_KINDS[suffix] ?: return@forEach
        // Одна воронка для всех кандидатов (#1139): обёртка снята, форма спрошена. Не
        // прошедшее проверку знанием не становится — ни фактом, ни узлом с действиями.
        val value = com.point.core.flow.factCandidate(key, e.value) ?: return@forEach

        // «Голое время это никогда не дата, это мусор» (#651): ни фактом, ни узлом.
        if (kind == KIND_DATE && bareTimestamp(value)) return@forEach

        val known = source.metadata[key]
        val sameAsKnown = known != null && com.point.core.flow.normConsensus(known) ==
            com.point.core.flow.normConsensus(value)
        when {
            known.isNullOrBlank() && key !in facts -> {
                facts[key] = value
                facts[key + META_SOURCE_SUFFIX] = source_.wire
                e.line?.let { facts[key + META_LINE_SUFFIX] = it }
            }
            sameAsKnown || com.point.core.flow.normConsensus(facts[key].orEmpty()) ==
                com.point.core.flow.normConsensus(value) -> Unit

            // То же знание другими словами — не второй объект области, а прочтение
            // первого: тождество факта в Point одно (#1122).
            known != null && com.point.core.flow.sameFact(key, known, value) -> Unit

            facts[key]?.let { com.point.core.flow.sameFact(key, it, value) } == true -> Unit

            else -> more.getOrPut(key) { mutableListOf() } += value
        }

        if (sameAsKnown) return@forEach
        val id = "${source.id}:$suffix:${value.filter(Char::isLetterOrDigit).uppercase()}"
        objects.getOrPut(id) {
            PointObject(
                id = id,
                mime = "text/plain",
                uri = ValueRef(value),
                state = ObjectState(kind, setOfNotNull(feature)),
                metadata = buildMap {
                    put(key, value)
                    put(com.point.core.flow.META_AT_REGION, at)
                    e.line?.let { put(key + META_LINE_SUFFIX, it) }
                },
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
