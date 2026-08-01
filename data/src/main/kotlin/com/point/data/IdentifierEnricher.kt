package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.META_ENTITY_TRACK
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.geoFacts
import com.point.core.flow.meterFacts
import com.point.core.flow.provenanceOf
import com.point.core.flow.trackFacts
import com.point.core.flow.waybillNumbers
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * «Find waybill numbers» — the first extractor of the object pipeline (#222).
 *
 * The first enricher that returns **objects** rather than flags. Mirrors [EntityEnricher] in
 * shape: TEXT only, so it also covers screenshots, since OCR yields a TEXT object this runs on.
 *
 * On-device, rule-based, no model and no network — hence the cheap wave. That matters beyond
 * speed: the number the user actually came for must not depend on a key, a quota or a signal.
 *
 * **Why it exists.** ML Kit reads `20 4514 9154 9395` off a parcel screenshot and calls it a
 * phone; the plausibility filter then correctly drops it — 14 digits is not something you dial.
 * The judgement is right and stays. What was missing was anyone to pick the number up
 * afterwards, so the single most useful thing on the screen fell through the floor.
 *
 * **Здесь же — остальные офлайновые правила формы** (#262): показание счётчика ([meterFacts]) и
 * координаты точки ([geoFacts]). Один дешёвый проход по тексту на все правила: у них общая
 * порода («форма совпала, и это ровно одна улика»), общий бюджет и общая судьба — они пишут
 * факты, по которым считается метрика корпуса, и не решают ничего сами.
 */
class IdentifierEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(
        cost = EnrichCost.FAST,
        mayYieldKinds = setOf(KIND_IDENTIFIER),
    )

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.TEXT

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        val text = runCatching { File(obj.uri.value).takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
            .take(MAX_CHARS)
        if (text.isBlank()) return@withContext EnrichmentDelta()

        // Один суд правила на весь вызов: факты трека и узлы графа обязаны говорить одно и то
        // же о происхождении и уликах — иначе узел разойдётся с фактом, из которого вырос (#264).
        val facts = trackFacts(text)
        val (objects, relations) = identifierObjects(obj, text, facts)
        // Показание счётчика и координаты (#262) — те же офлайновые правила формы в том же
        // дешёвом проходе. Узлами графа они пока не становятся (действия, которое по ним
        // поедет, ещё нет), но фактами — обязаны: схемы «Передать показание» и «Построить
        // маршрут» считаются по метаданным, а не по графу.
        val ruleFacts = facts + meterFacts(text) + geoFacts(text)
        if (objects.isEmpty() && ruleFacts.isEmpty()) return@withContext EnrichmentDelta()
        // Трек — и факт, а не только узел графа (#260): схема «Отследить отправление» читает
        // `entity.track` из метаданных, второй похожий номер честно виден в `.alt` (v3 §8).
        EnrichmentDelta(objects = objects, relations = relations, metadata = ruleFacts)
    }

    private companion object {
        const val MAX_CHARS = 20_000
    }
}

/**
 * Waybill numbers in already-read text → graph nodes.
 *
 * Shared with [OcrEnricher] on purpose. The rule first shipped as a TEXT-only enricher, and on a
 * real device that meant it **never ran**: what the user shares is an IMAGE, its text lives as an
 * OCR sidecar, and a TEXT object only appears if they tap «Распознать текст». The single most
 * useful thing on a parcel screenshot fell through the same floor twice, for a different reason
 * each time. Every path that produces text now goes through here.
 *
 * **Узел несёт срез фактов трека** ([facts], #264): собственное значение плюс происхождение и
 * улики, которыми правило судило форму. Значение у каждого узла своё (на странице бывает второй
 * настоящий номер), а происхождение и улики — общие: их выдал один и тот же прогон одного и того
 * же правила. `provenance` читается из этого же среза, поэтому поле и `.src` разойтись не могут.
 */
internal fun identifierObjects(
    source: PointObject,
    text: String,
    facts: Map<String, String> = trackFacts(text),
): Pair<List<PointObject>, List<Relation>> {
    val objects = waybillNumbers(text).map { value ->
        val slice = buildMap {
            put(META_ENTITY_TRACK, value)
            facts[META_ENTITY_TRACK + META_SOURCE_SUFFIX]
                ?.let { put(META_ENTITY_TRACK + META_SOURCE_SUFFIX, it) }
            facts[META_ENTITY_TRACK + META_EVIDENCE_SUFFIX]
                ?.let { put(META_ENTITY_TRACK + META_EVIDENCE_SUFFIX, it) }
        }
        PointObject(
            id = identifierId(source.id, value),
            mime = "text/plain",
            // No file behind it: the value IS the content (#222).
            uri = ValueRef(value),
            state = ObjectState(KIND_IDENTIFIER),
            metadata = slice,
            // ПРОЧИТАНО, а не ВЫВЕДЕНО: правило нашло эти цифры дословно в распознанном тексте —
            // то же самое, что оно объявило факту строкой выше (#264). Расхождение узла с
            // родителем было бы ровно той болезнью, от которой лечит срез.
            provenance = provenanceOf(slice, META_ENTITY_TRACK),
            sourceObjects = listOf(source.id),
            creatorAction = IDENTIFIER_CREATOR,
        )
    }
    // Provenance, not meaning: «this number was read off that page». What the number identifies
    // and who issued it is for a classifier to say later, in code.
    return objects to objects.map { Relation(it.id, RelationType.FOUND_IN, source.id) }
}

internal const val IDENTIFIER_CREATOR = "identifier-enricher"

/** Deterministic: re-running enrichment on the same object must not double the graph. */
private fun identifierId(sourceId: String, value: String) =
    "$sourceId:identifier:${value.filter(Char::isDigit)}"
