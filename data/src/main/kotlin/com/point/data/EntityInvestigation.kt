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

class EntityInvestigationRealizer(
    private val extractor: EntityExtractor,

    // Тестовый планировщик видит IO-работу целиком: хвост на реальном пуле доживал
    // после конца теста и ронял соседний (UncaughtExceptionsBeforeTest, 2026-08-09).
    private val io: kotlin.coroutines.CoroutineContext,
) : Realizer {

    @Inject constructor(extractor: EntityExtractor) : this(extractor, Dispatchers.IO)

    override val capabilityId = EntityInvestigation.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching { findings(input) }.fold(
            onSuccess = { ActionResult.Done("", it) },

            onFailure = { ActionResult.Failure(it.message ?: FAILED, recoverable = true) },
        )

    private suspend fun findings(obj: PointObject): Findings = withContext(io) {
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
        val value = e.value.trim().takeIf { it.isNotBlank() } ?: return@forEach

        // «Голое время это никогда не дата, это мусор» (#651): ни фактом, ни узлом.
        if (kind == KIND_DATE && bareTimestamp(value)) return@forEach

        val known = source.metadata[key]
        val sameAsKnown = known != null && com.point.core.flow.normConsensus(known) ==
            com.point.core.flow.normConsensus(value)
        when {
            known.isNullOrBlank() && key !in facts -> {
                facts[key] = value
                e.line?.let { facts[key + META_LINE_SUFFIX] = it }
            }
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

internal fun entityDelta(
    source: PointObject,
    entities: List<com.point.core.flow.Entity>,
    text: String = "",
): Findings {

    // «Голое время это никогда не дата, это мусор» (#651): и признака HAS_DATE не даёт.
    // Неправдоподобный адрес извне (#632: «Розчинник Уайт-Спірит ХімРезерв 1л» от
    // ML Kit) отбрасывается целиком — расширение до строки лишь усиливало ошибку;
    // настоящий адрес в тексте найдёт правило-читатель (addressFacts) ниже.
    val meaningful = entities.filterNot {
        it.type == com.point.core.flow.EntityType.DATE_TIME && it.isBareClock()
    }.filterNot { e ->
        e.type == com.point.core.flow.EntityType.ADDRESS &&
            !com.point.core.flow.plausibleAddress(expandAddressToLine(e.value, text)) &&
            !com.point.core.flow.plausibleAddress(e.value)
    }
    val features = meaningful.mapNotNullTo(mutableSetOf()) { it.type.asFeature() }

    // Второе значение того же вида — «ещё один», а не проигравший и не спор (S6,
    // живой прогон 2026-08-09): два телефона в тексте остаются двумя телефонами.
    val more = LinkedHashMap<String, MutableList<String>>()

    // Строка документа вокруг значения — подпись при нём (#782): видно, что это за день,
    // но значением она не является и в спор, в «ещё» и в тождество узла не входит.
    val lines = LinkedHashMap<String, String>()
    val extracted = buildMap {

        meaningful.sortedBy { it.isBareClock() }.forEach { e ->
            e.type.asMetaKey()?.let { key ->

                val value = if (e.type == com.point.core.flow.EntityType.ADDRESS && text.isNotEmpty()) {
                    expandAddressToLine(e.value, text)
                } else {
                    e.value
                }
                e.line?.let { lines.putIfAbsent(value, it) }
                val first = this[key]
                if (first == null) {
                    put(key, value)
                } else if (normConsensus(value) != normConsensus(first)) {
                    val bucket = more.getOrPut(key) { mutableListOf() }
                    if (bucket.none { normConsensus(it) == normConsensus(value) }) bucket += value
                }
            }
        }
    }
    val moreFacts = more.mapKeys { (key, _) -> key + META_MORE_SUFFIX }
        .mapValues { (_, values) -> altValue(values) }

    val ruled = if (META_ENTITY_ADDRESS in extracted) emptyMap() else addressFacts(text)
    val captions = extracted.mapNotNull { (key, value) ->
        lines[value]?.let { key + META_LINE_SUFFIX to it }
    }.toMap()
    val facts = extracted + moreFacts + ruled + captions

    if (ruled.isNotEmpty()) features += Feature.HAS_ADDRESS
    val (objects, relations) = entityObjects(source, facts, creator = ENTITY_CREATOR, lines = lines)
    return Findings(features, facts, objects, relations)
}

/**
 * Один день — один узел (#660): узлы дат схлопываются по календарному дню,
 * побеждает более информативное значение (с временем). Остальные виды остаются
 * как есть — их тождество решается общей нормализацией.
 */
private fun List<PointObject>.dedupedNodes(
    kind: com.point.core.model.ObjectKind,
    key: String,
): List<PointObject> {
    if (kind != KIND_DATE) return this
    val byDay = LinkedHashMap<String, PointObject>()
    forEach { node ->
        val value = node.metadata[key].orEmpty()
        val day = com.point.core.flow.humanDayOf(value)?.toString() ?: normConsensus(value)
        val kept = byDay[day]
        if (kept == null || value.length > kept.metadata[key].orEmpty().length) byDay[day] = node
    }
    return byDay.values.toList()
}

internal const val ENTITY_CREATOR = "entity-enricher"

/**
 * Голая отметка времени — не дата (#244, самый частый ложный chip корпуса): часы «18:02»
 * без даты рядом и словесные относительные «вчера»/«сегодня» из хрома переписок и статус-бара.
 * Знание остаётся — факт и признак живут (заметка «15:12 Встреча…» ими пользуется), но объект
 * «Дата» из такого значения не рождается: chip обещал бы дату, которой на кадре нет.
 * Значения с датой рядом («завтра о 09:00», «29.07 до 18:00») — не отметка, а срок.
 */
internal fun bareTimestamp(value: String): Boolean {
    val v = value.trim()
    return com.point.core.flow.bareClock(v) || CHROME_RELATIVE_DAY.matches(v)
}

private val CHROME_RELATIVE_DAY =
    Regex("""(?iu)(?:вчера|сегодня|вчора|сьогодні|yesterday|today)[.,!]?""")

internal fun entityObjects(
    source: PointObject,
    facts: Map<String, String>,
    creator: String,

    /** Строка документа для значения — подпись узла, не его значение (#782). */
    lines: Map<String, String> = emptyMap(),
): Pair<List<PointObject>, List<Relation>> {

    if (source.state.kind in EXTRACTED_KINDS) return emptyList<PointObject>() to emptyList()

    val objects = ENTITY_KINDS.flatMap { (suffix, kindAndFeature) ->
        val key = META_ENTITY_PREFIX + suffix
        val value = facts[key]?.takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
        val (kind, feature) = kindAndFeature

        val alternatives = alternativesOf(facts, key)

        fun node(id: String, nodeValue: String, withAlternatives: Boolean) = PointObject(
            id = id,
            mime = "text/plain",
            uri = ValueRef(nodeValue),
            state = ObjectState(kind, setOf(feature)),
            metadata = buildMap {
                put(key, nodeValue)
                if (withAlternatives && alternatives.isNotEmpty()) {
                    put(key + META_ALT_SUFFIX, altValue(alternatives))
                }

                facts[key + META_EVIDENCE_SUFFIX]?.let { put(key + META_EVIDENCE_SUFFIX, it) }
                facts[key + META_SOURCE_SUFFIX]?.let { put(key + META_SOURCE_SUFFIX, it) }

                // Подпись принадлежит значению узла, а не полю вообще: «ещё»-значение
                // несёт свою строку документа, а не строку соседа (#782).
                (lines[nodeValue] ?: facts[key + META_LINE_SUFFIX]?.takeIf { nodeValue == value })
                    ?.let { put(key + META_LINE_SUFFIX, it) }
            },
            provenance = provenanceOf(facts, key),
            sourceObjects = listOf(source.id),
            creatorAction = creator,
        )

        val primary = if (kind == KIND_DATE && bareTimestamp(value)) {
            null
        } else {
            node("${source.id}:$suffix", value, withAlternatives = true)
        }

        // «Ещё значения» того же вида — самостоятельные объекты со значением в
        // идентичности (прецедент focused/identifiers): второй телефон — не спор.
        val others = moreOf(facts, key)
            .filter { !(kind == KIND_DATE && bareTimestamp(it)) }
            .map { extra -> node("${source.id}:$suffix:$extra", extra, withAlternatives = false) }

        // «Один день — один узел» (#660, решение владельца): «26.04.2026» и
        // «26.04.2026 20:04» — одна дата; побеждает значение с временем, оно
        // информативнее. Другие виды дедупятся по своей нормализации.
        (listOfNotNull(primary) + others).dedupedNodes(kind, key)
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
