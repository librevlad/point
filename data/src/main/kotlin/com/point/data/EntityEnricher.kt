package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
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

/**
 * Flags actionable entities in a TEXT object (on-device via [EntityExtractor]) so targeted actions —
 * Позвонить, Сообщение, Написать письмо — appear as bubbles after first paint. Mirrors
 * [TextUrlEnricher]; works on OCR'd screenshots too, since OCR yields a TEXT object this runs on.
 */
class EntityEnricher @Inject constructor(
    private val extractor: EntityExtractor,
) : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.FAST,
        mayYield = setOf(
            Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS, Feature.HAS_DATE, Feature.HAS_CARD,
        ),
        mayYieldKinds = setOf(KIND_PHONE, KIND_EMAIL, KIND_URL, KIND_ADDRESS, KIND_DATE),
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val text = runCatching { File(obj.uri.value).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
            .take(MAX_CHARS)
        if (text.isBlank()) return@withContext EnrichmentDelta()
        entityDelta(obj, extractor.extract(text), text)
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}

/** Entities → one delta: features to flag, understood facts (the first value per kind,
 *  `entity.*`) for the «Point понял» checklist, and the same facts as graph objects (#222).
 *  Shared by the text and OCR enrichers. */
internal fun entityDelta(
    source: PointObject,
    entities: List<com.point.core.flow.Entity>,
    text: String = "",
): EnrichmentDelta {
    val features = entities.mapNotNullTo(mutableSetOf()) { it.type.asFeature() }
    val extracted = buildMap {
        // #244: место «Дата» одно, а экран переписки состоит из времён почти целиком — любое
        // из них вытесняло настоящую дату, которая на том же кадре есть (`30.03`, `01.04`).
        // Голое время не выбрасывается: оно остаётся уликой и признаком HAS_DATE (заметка
        // «15:12 Встреча с Петром» — #233), но встаёт в очереди последним. Сортировка
        // стабильная, поэтому порядок всех прочих сущностей сохраняется.
        entities.sortedBy { it.isBareClock() }.forEach { e ->
            e.type.asMetaKey()?.let { key ->
                // #236: an address loses its settlement in extraction («Олексйвка, вул. Сонячна, 15»
                // comes back as «вул. Сонячна, 15») and the map then offers four towns. What was
                // lost is on the same line — give it back here, on device, for nothing.
                val value = if (e.type == com.point.core.flow.EntityType.ADDRESS && text.isNotEmpty()) {
                    expandAddressToLine(e.value, text)
                } else {
                    e.value
                }
                putIfAbsent(key, value)
            }
        }
    }
    // #262, кадры 12 и 14: адресом до сих пор считалось только то, что назвал извлекатель, а на
    // скрине карты он молчит — «Бритвка, Центральна, 586» стоит в тексте целиком и адресом не
    // признано. Офлайновое правило формы ([addressLines]) читает такую строку бесплатно, но
    // **только там, где первый читатель промолчал**: два писателя одного ключа в одной волне
    // обогащения гонялись бы за значение, и человек видел бы то один адрес, то другой.
    val ruled = if (META_ENTITY_ADDRESS in extracted) emptyMap() else addressFacts(text)
    val facts = extracted + ruled
    // Признак зажигается вместе с фактом: без него «Открыть на карте» не появится, и прочитанный
    // адрес снова провалился бы в пол — та же болезнь, что чинил #222 для номера накладной.
    if (ruled.isNotEmpty()) features += Feature.HAS_ADDRESS
    val (objects, relations) = entityObjects(source, facts, creator = ENTITY_CREATOR)
    return EnrichmentDelta(features, facts, objects, relations)
}

internal const val ENTITY_CREATOR = "entity-enricher"

/**
 * `entity.*` facts → world-objects (#222): the branch address stops being a line of text and
 * becomes a thing you can tap.
 *
 * Each object carries **both** its kind and the feature that fact used to be — so
 * `MapCapability.accepts = state.has(HAS_ADDRESS)` lights «Маршрут» on the address itself with
 * no new action code. That is the whole point of the migration: the graph reuses the 44
 * capabilities already written, it does not replace them.
 *
 * The id is `<source>:<suffix>`, so re-running enrichment — or the same fact arriving from both
 * the extractor and stored metadata — collapses to one node instead of growing the graph.
 *
 * **`entity.card` is deliberately absent.** A card number is the one fact the UI takes care to
 * mask («•• 5678»); promoting it to a first-class object would put it on screen in full.
 *
 * **Узел несёт срез метаданных своего факта** (#264): значение, спор (`.alt`), улики (`.ev`) и
 * происхождение (`.src`) родителя — и `provenance` выводится из этого же среза, а не выставляется
 * рядом. Это и есть защита от двух источников истины: поле узла и его `.src` разойтись не могут.
 */
internal fun entityObjects(
    source: PointObject,
    facts: Map<String, String>,
    creator: String,
): Pair<List<PointObject>, List<Relation>> {
    // An address does not contain itself. Without this, every tap into a found object would
    // find the same fact one level deeper, forever.
    if (source.state.kind in EXTRACTED_KINDS) return emptyList<PointObject>() to emptyList()

    val objects = ENTITY_KINDS.mapNotNull { (suffix, kindAndFeature) ->
        val key = META_ENTITY_PREFIX + suffix
        val value = facts[key]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val (kind, feature) = kindAndFeature
        // #222, шаг 7: when two sources read this fact differently, the object says so instead
        // of presenting one of them as settled.
        val alternatives = alternativesOf(facts, key)
        // Kept so the object's own screen shows its value, and so a re-open re-lights
        // the feature through MetadataEntityEnricher without re-running any engine.
        val slice = buildMap {
            put(key, value)
            if (alternatives.isNotEmpty()) {
                put(key + META_ALT_SUFFIX, altValue(alternatives))
            }
            // Улики и происхождение — родительские: узел судится ровно тем же, чем судился
            // факт, из которого он вырос (#264). Чего у родителя нет, того нет и здесь —
            // «не судили» не становится «улик нет».
            facts[key + META_EVIDENCE_SUFFIX]?.let { put(key + META_EVIDENCE_SUFFIX, it) }
            facts[key + META_SOURCE_SUFFIX]?.let { put(key + META_SOURCE_SUFFIX, it) }
        }
        PointObject(
            id = "${source.id}:$suffix",
            mime = "text/plain",
            uri = ValueRef(value), // no file behind it — the value IS the content
            state = ObjectState(kind, setOf(feature)),
            metadata = slice,
            provenance = provenanceOf(slice, key),
            sourceObjects = listOf(source.id),
            creatorAction = creator,
        )
    }
    return objects to objects.map { Relation(it.id, RelationType.FOUND_IN, source.id) }
}

/** Which `entity.*` facts become objects, and the feature each one carries with it. */
private val ENTITY_KINDS: Map<String, Pair<ObjectKind, Feature>> = mapOf(
    "phone" to (KIND_PHONE to Feature.HAS_PHONE),
    "email" to (KIND_EMAIL to Feature.HAS_EMAIL),
    "url" to (KIND_URL to Feature.HAS_URL),
    "address" to (KIND_ADDRESS to Feature.HAS_ADDRESS),
    "date" to (KIND_DATE to Feature.HAS_DATE),
)
