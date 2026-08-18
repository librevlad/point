package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.META_ENTITY_TRACK
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.amountFacts
import com.point.core.flow.geoFacts
import com.point.core.flow.meterFacts
import com.point.core.flow.provenanceOf
import com.point.core.flow.receiptFacts
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

class IdentifierInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
        mayYieldKinds = setOf(KIND_IDENTIFIER),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("identifiers")
    }
}

class IdentifierInvestigationRealizer @Inject constructor() : Realizer {

    override val capabilityId = IdentifierInvestigation.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        com.point.core.flow.investigated { findings(input) }

    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {
        val file = File(obj.uri.value)

        if (!file.isFile) error(com.point.core.flow.NO_TEXT_PAYLOAD)
        val text = file.readText().take(com.point.core.flow.INVESTIGATION_TEXT_CHARS)
        if (text.isBlank()) return@withContext Findings()

        // Значение не бывает надёжнее источника: путь у него тот же, каким пришёл текст
        // (#1127). Прежде любое значение из текстового объекта помечалось «прочитано с
        // кадра» — и координаты, взятые у системы геолокации, и дата из набранной строки.
        val source = obj.provenance

        val facts = trackFacts(text, source)
        val (objects, relations) = identifierObjects(obj, text, facts)

        val ruleFacts = facts + meterFacts(text, source) + geoFacts(text, source) +
            amountFacts(text, source) + receiptFacts(text, source)
        if (objects.isEmpty() && ruleFacts.isEmpty()) return@withContext Findings()

        Findings(objects = objects, relations = relations, metadata = ruleFacts)
    }

    private companion object {
    }
}

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

            uri = ValueRef(value),
            state = ObjectState(KIND_IDENTIFIER),
            metadata = slice,

            provenance = provenanceOf(slice, META_ENTITY_TRACK),
            sourceObjects = listOf(source.id),
            creatorAction = IDENTIFIER_CREATOR,
        )
    }

    return objects to objects.map { Relation(it.id, RelationType.FOUND_IN, source.id) }
}

internal const val IDENTIFIER_CREATOR = "identifier-enricher"

private fun identifierId(sourceId: String, value: String) =
    "$sourceId:identifier:${value.filter(Char::isLetterOrDigit).uppercase()}"


