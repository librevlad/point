package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.Findings
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ValueRef
import com.point.core.model.Relation
import com.point.core.model.RelationType

/**
 * Общая семантическая воронка сущностей — одна на телефон и компьютер (#1139, #1144).
 *
 * Жила в `:data`, и компьютер, не видя её, держал свою упрощённую копию: победителем
 * становилось первое значение по тексту, спор двух прочтений не замечался, происхождение
 * ставилось жёстко «прочитано». Здесь правила написаны один раз:
 * кандидат → проверка формы → sameFact → fullerReading → main/.more/.alt → происхождение.
 */
fun entityDelta(
    source: PointObject,
    entities: List<com.point.core.flow.Entity>,
    text: String = "",

    /**
     * Каким путём пришло значение (#990). Тот, кто кладёт знание, обязан сказать, откуда он
     * его взял: прочитанное с кадра — `OCR`, вычитанное из присланного текста — путь самого
     * объекта. Прежде сущности заводились без `.src` вовсе и стояли у человека без галочки,
     * как знание неизвестного происхождения.
     */
    source_: com.point.core.model.Provenance = source.provenance,
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

    // А вот второе прочтение ТОГО ЖЕ факта — спор, и он обязан остаться виден (#1122).
    // На накладной адрес прочитан дважды, во второй раз с прилипшим отчеством получателя,
    // и человеку показывали два места, которых на бумаге одно.
    val disputed = LinkedHashMap<String, MutableList<String>>()

    // Строка документа вокруг значения — подпись при нём (#782): видно, что это за день,
    // но значением она не является и в спор, в «ещё» и в тождество узла не входит.
    val lines = LinkedHashMap<String, String>()
    val extracted = buildMap {

        meaningful.sortedBy { it.isBareClock() }.forEach { e ->
            e.type.asMetaKey()?.let { key ->

                val raw = if (e.type == com.point.core.flow.EntityType.ADDRESS && text.isNotEmpty()) {
                    expandAddressToLine(e.value, text)
                } else {
                    e.value
                }

                // Та же воронка, что и у знания области (#1139): кандидат, не прошедший
                // проверку формы, дальше не идёт.
                val value = com.point.core.flow.factCandidate(key, raw) ?: return@let
                e.line?.let { lines.putIfAbsent(value, it) }
                val first = this[key]
                when {
                    first == null -> put(key, value)

                    normConsensus(value) == normConsensus(first) -> Unit

                    com.point.core.flow.sameFact(key, first, value) -> {
                        val fuller = com.point.core.flow.fullerReading(first, value)
                        val other = if (fuller == first) value else first
                        put(key, fuller)
                        val bucket = disputed.getOrPut(key) { mutableListOf() }
                        if (bucket.none { normConsensus(it) == normConsensus(other) }) bucket += other
                    }

                    else -> {
                        val bucket = more.getOrPut(key) { mutableListOf() }
                        if (bucket.none { normConsensus(it) == normConsensus(value) }) bucket += value
                    }
                }
            }
        }
    }
    val moreFacts = more.mapKeys { (key, _) -> key + META_MORE_SUFFIX }
        .mapValues { (_, values) -> altValue(values) }

    val disputes = disputed.mapKeys { (key, _) -> key + META_ALT_SUFFIX }
        .mapValues { (_, values) -> altValue(values) }

    val told = extracted.keys.associate { it + META_SOURCE_SUFFIX to source_.wire }
    val ruled = if (META_ENTITY_ADDRESS in extracted) emptyMap() else addressFacts(text, source_)
    val captions = extracted.mapNotNull { (key, value) ->
        lines[value]?.let { key + META_LINE_SUFFIX to it }
    }.toMap()
    val facts = extracted + told + moreFacts + disputes + ruled + captions

    if (ruled.isNotEmpty()) features += Feature.HAS_ADDRESS
    val (objects, relations) = entityObjects(source, facts, creator = ENTITY_CREATOR, lines = lines)
    return Findings(features, facts, objects, relations)
}

/**
 * Один факт — один узел (#660, #1122).
 *
 * Правило начиналось с дат — «26.04.2026» и «26.04.2026 20:04» один день, — но оно не про
 * даты: два прочтения одного адреса тоже один адрес, и человеку показывали их как два места.
 * Тождество считает [com.point.core.flow.sameFact], побеждает более информативное прочтение.
 */
internal fun List<PointObject>.dedupedNodes(
    @Suppress("UNUSED_PARAMETER") kind: com.point.core.model.ObjectKind,
    key: String,
): List<PointObject> {
    val kept = mutableListOf<PointObject>()
    forEach { node ->
        val value = node.metadata[key].orEmpty()
        val twin = kept.indexOfFirst { com.point.core.flow.sameFact(key, it.metadata[key].orEmpty(), value) }
        if (twin < 0) {
            kept += node
            return@forEach
        }
        val known = kept[twin].metadata[key].orEmpty()
        if (com.point.core.flow.fullerReading(known, value) != known) kept[twin] = node
    }
    return kept.toList()
}

const val ENTITY_CREATOR = "entity-enricher"

/**
 * Голая отметка времени — не дата (#244, самый частый ложный chip корпуса): часы «18:02»
 * без даты рядом и словесные относительные «вчера»/«сегодня» из хрома переписок и статус-бара.
 * Знание остаётся — факт и признак живут (заметка «15:12 Встреча…» ими пользуется), но объект
 * «Дата» из такого значения не рождается: chip обещал бы дату, которой на кадре нет.
 * Значения с датой рядом («завтра о 09:00», «29.07 до 18:00») — не отметка, а срок.
 */
fun bareTimestamp(value: String): Boolean {
    val v = value.trim()
    return com.point.core.flow.bareClock(v) || CHROME_RELATIVE_DAY.matches(v)
}

private val CHROME_RELATIVE_DAY =
    Regex("""(?iu)(?:вчера|сегодня|вчора|сьогодні|yesterday|today)[.,!]?""")

fun entityObjects(
    source: PointObject,
    facts: Map<String, String>,
    creator: String,

    /** Строка документа для значения — подпись узла, не его значение (#782). */
    lines: Map<String, String> = emptyMap(),
): Pair<List<PointObject>, List<Relation>> {

    if (source.state.kind in EXTRACTED_KINDS) return emptyList<PointObject>() to emptyList()

    val objects = ENTITY_KINDS.flatMap { (suffix, node) ->
        val key = META_ENTITY_PREFIX + suffix
        val value = facts[key]?.takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
        val (kind, feature) = node

        val alternatives = alternativesOf(facts, key)

        fun node(id: String, nodeValue: String, withAlternatives: Boolean) = PointObject(
            id = id,
            mime = "text/plain",
            uri = ValueRef(nodeValue),
            state = ObjectState(kind, setOfNotNull(feature)),
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

    // Принадлежность факта — связь между узлами (#1176): на документе она аннотация ключа,
    // в графе — та же связь между найденным значением и стороной, которой оно принадлежит.
    // Обещания без связи не бывает: пока `.of` не назван, узел стоит сам по себе.
    val owned = ENTITY_KINDS.keys.mapNotNull { suffix ->
        val key = META_ENTITY_PREFIX + suffix
        val owner = ownerOf(facts, key)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val id = "${source.id}:$suffix"
        if (objects.none { it.id == id }) return@mapNotNull null
        Relation(id, RelationType.BELONGS_TO, partyNodeId(source.id, owner))
    }
    return objects to (objects.map { Relation(it.id, RelationType.FOUND_IN, source.id) } + owned)
}

/**
 * Во что можно войти (#947).
 *
 * Объектом становились пять видов сущностей из пятнадцати: телефон, почта, ссылка, адрес,
 * дата. Прочитанный с кадра штрихкод, сумма счёта, показание счётчика, номер квитанции,
 * координаты и место оставались строкой знания — войти в них было нельзя, а конституция
 * говорит ровно обратное: человек входит в найденный объект и продолжает его понимание.
 *
 * Признак `Feature` есть не у каждого вида, и выдумывать его ради узла незачем: узлу нужен
 * вид, а признак — это про то, что умеет исходник.
 *
 * Коды разного назначения — один вид: накладная, штрихкод, счётчик, квитанция и карта — всё
 * это идентификаторы. Новый вид заводится, только когда он и правда другой: сумма это деньги,
 * место это «где».
 *
 * Карты здесь нет намеренно и по-прежнему: в номер карты не входят, им расплачиваются.
 */
/** Вид узла и признак исходника, если он у этого вида есть. */
data class NodeKind(val kind: ObjectKind, val feature: Feature? = null)

val ENTITY_KINDS: Map<String, NodeKind> = mapOf(
    "phone" to NodeKind(KIND_PHONE, Feature.HAS_PHONE),
    "email" to NodeKind(KIND_EMAIL, Feature.HAS_EMAIL),
    "url" to NodeKind(KIND_URL, Feature.HAS_URL),
    "address" to NodeKind(KIND_ADDRESS, Feature.HAS_ADDRESS),
    "date" to NodeKind(KIND_DATE, Feature.HAS_DATE),

    "amount" to NodeKind(com.point.core.flow.KIND_AMOUNT),
    "barcode" to NodeKind(com.point.core.flow.KIND_IDENTIFIER, Feature.HAS_BARCODE),
    "meter" to NodeKind(com.point.core.flow.KIND_IDENTIFIER),
    "receipt" to NodeKind(com.point.core.flow.KIND_IDENTIFIER),
    "serial" to NodeKind(com.point.core.flow.KIND_IDENTIFIER),
    "geo" to NodeKind(com.point.core.flow.KIND_PLACE, Feature.HAS_GEO),
    "place" to NodeKind(com.point.core.flow.KIND_PLACE),
)



