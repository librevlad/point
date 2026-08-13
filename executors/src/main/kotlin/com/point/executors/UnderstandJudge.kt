package com.point.executors

import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ClassifierRole
import com.point.core.flow.Cost
import com.point.core.flow.EvidenceClass
import com.point.core.flow.FieldCandidate
import com.point.core.flow.InvestigationState
import com.point.core.flow.KNOWN_SEMANTIC_TAGS
import com.point.core.flow.Latency
import com.point.core.flow.LayoutElement
import com.point.core.flow.LlmClient
import com.point.core.flow.MAX_FIELD_CANDIDATES
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_BLOCKED_SUFFIX
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_CHARS
import com.point.core.flow.META_READ_TOTAL_CHARS
import com.point.core.flow.investigationOutcome
import com.point.core.flow.partialReadMessage
import com.point.core.flow.readProgressOf
import com.point.core.flow.readWindowOf
import com.point.core.flow.withInvestigation
import com.point.core.flow.ReadingMode
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.ParsedUnderstanding
import com.point.core.flow.Realizer
import com.point.core.flow.UNDERSTAND_CONTRACT_KEYS
import com.point.core.flow.parseFieldCandidates
import com.point.core.flow.saysNothing
import com.point.core.flow.splitCandidate
import com.point.core.flow.AtomAddress
import com.point.core.flow.altValue
import com.point.core.flow.alternativesOf
import com.point.core.flow.bareIndexId
import com.point.core.flow.fieldEvidence
import com.point.core.flow.formEvidence
import com.point.core.flow.isRepairOf
import com.point.core.flow.isRoleLabel
import com.point.core.flow.layoutOf
import com.point.core.flow.mergeFacts
import com.point.core.flow.normConsensus
import com.point.core.flow.parseClassification
import com.point.core.flow.promptIndex
import com.point.core.flow.reportStage
import com.point.core.flow.provenanceOf
import com.point.core.flow.resolve
import com.point.core.flow.ruleEvidence
import com.point.core.flow.s10CheckDigitValid
import com.point.core.flow.META_ENTITY_PLACE
import com.point.core.flow.placeOfReceiver
import com.point.core.flow.phoneOwners
import com.point.core.flow.foundLiterally
import com.point.core.flow.standsInReadText
import com.point.core.flow.semanticFits
import com.point.core.flow.labelNeedingKey
import com.point.core.model.ActionResult
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.plausiblePersonName
import com.point.core.model.Findings
import com.point.core.model.Relation
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Рассудить: что из названного моделью становится знанием (#835).
 *
 * Опора в прочитанном (#809), форма значения (#657), спор кандидатов, происхождение.
 * Чистая логика: ни сети, ни файлов — её можно проверять напрямую.
 */
internal fun judgeFields(
    fields: Map<String, List<FieldCandidate>>,
    layer: AtomLayer?,

    /**
     * Всё, что Point прочитал сам: слой слов снимка или текст самого объекта (#809). Пусто —
     * сверять не с чем, и значение принимается как раньше.
     */
    readText: String = layer?.text.orEmpty(),
): JudgedFields {
    val ruleMarks = layer?.ruleEvidence().orEmpty()
    val won = LinkedHashMap<String, JudgedField>()
    val retry = mutableSetOf<String>()
    val blockedByKey = LinkedHashMap<String, List<String>>()
    fields.forEach { (key, rawCandidates) ->
        val grounded = rawCandidates.flatMap { groundCandidate(key, it, layer, readText) }
            .distinctBy { it.first.text to it.first.ids }
        val (blocked, alive) = grounded.partition { (c, _) -> s10CheckDigitValid(c.text) == false }
        if (blocked.isNotEmpty()) blockedByKey[key] = blocked.map { it.first.text }
        if (alive.isEmpty()) {
            if (grounded.isNotEmpty()) retry += key
            return@forEach
        }
        val scored = alive.map { (c, isGrounded) ->

            val evidence = layer?.fieldEvidence(key, c, ruleMarks) ?: formEvidence(key, c.text)
            Triple(c, isGrounded, evidence)
        }
        val winner = scored.maxByOrNull { it.third.size }!!
        won[key] = JudgedField(
            text = winner.first.text,
            evidence = winner.third,
            grounded = winner.second,
            candidates = scored.map { it.first.text }.distinct(),
            line = winner.first.line,
        )
    }
    return JudgedFields(won, retry, blockedByKey)
}

private fun groundCandidate(
    key: String,
    candidate: FieldCandidate,
    layer: AtomLayer?,
    readText: String,
): List<Pair<FieldCandidate, Boolean>> {
    if (layer == null || candidate.ids.isEmpty()) return withoutMarks(candidate, readText)
    val resolved = layer.resolve(AtomAddress.ByIds(candidate.ids.map(::bareIndexId)))
    if (resolved.atoms.isEmpty()) return withoutMarks(candidate, readText)
    val page = resolved.text
    val model = candidate.text
    return when {
        normConsensus(model) == normConsensus(page) -> listOf(candidate.copy(text = page) to true)
        isRepairOf(page, model) -> listOf(candidate to true)
        semanticFits(key, page) == false && semanticFits(key, model) == true ->
            listOf(FieldCandidate(model) to false)
        else -> listOf(
            candidate.copy(text = page) to true,
            FieldCandidate(model) to false,
        )
    }
}

/**
 * Значение, за которым нет меток слов, — «нет в тексте, нет знания» (#809, решение владельца
 * 12.08.2026).
 *
 * Модель обязана называть метки слов, когда слова значения есть на странице; без меток
 * значение раньше принималось как есть, и выдуманная дата вставала рядом с настоящей. Теперь
 * оно принимается, только если стоит в прочитанном тексте: дословно — тогда это слово
 * страницы, или как починка искажения — тогда это слово модели.
 */
private fun withoutMarks(
    candidate: FieldCandidate,
    readText: String,
): List<Pair<FieldCandidate, Boolean>> {
    val bare = candidate.copy(ids = emptyList())
    return when {

        // Читать нечем — сверять не с чем: снимок без распознанных слов читается глазами,
        // и там всё остаётся как было (#664).
        readText.isBlank() -> listOf(bare to false)

        // Слово страницы — значение прочитано; починка искажения — слово модели (#809).
        foundLiterally(candidate.text, readText) -> listOf(bare to true)
        standsInReadText(candidate.text, readText) -> listOf(bare to false)
        else -> emptyList()
    }
}
