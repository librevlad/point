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
import com.point.core.flow.ReadingMode
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.Realizer
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
import com.point.core.flow.semanticFits
import com.point.core.flow.labelNeedingKey
import com.point.core.model.ActionResult
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

private val CONTRACT_KEYS = mapOf(
    "PHONE" to "phone",
    "EMAIL" to "email",
    "URL" to "url",
    "ADDRESS" to "address",
    "DATE" to "date",
    "CARD" to "card",
    "TRACK" to "track",
    "METER" to "meter",
    "GEO" to "geo",
    "PLACE" to "place",
    "AMOUNT" to "amount",
    "RECEIPT" to "receipt",
    "SUBJECT" to "subject",
)

internal fun understandPrompt(
    elements: List<LayoutElement>,
    roles: List<ClassifierRole> = CLASSIFIER_ROLES,
    index: String? = null,
): String = buildString {
    append("Текст распознан с фотографии и может содержать ошибки распознавания. ")
    append("Ниже его элементы, у каждого свой идентификатор.\n\n")
    elements.forEach { append(it.id).append(": ").append(it.text).append('\n') }
    if (index != null) {
        append(
            "\nСлова страницы, каждое с меткой (атрибут rule= — подсказка офлайн-правила о " +
                "форме слова; она может ошибаться и ничего не решает):\n",
        )
        append(index).append('\n')
    }
    append("\nСделай две вещи.\n\n")
    append(
        "1) Найди контактные данные и номера. Значение приводи ПОЛНОСТЬЮ, как оно есть в " +
            "документе (адрес — вместе с населённым пунктом), и исправляй только явные искажения " +
            "распознавания. НИЧЕГО не додумывай: если чего-то в тексте нет — не пиши строку. " +
            "Цифры не меняй. Отвечай строками вида KEY=значение, по одной на строку. " +
            "Разрешённые KEY: PHONE, EMAIL, URL, ADDRESS, DATE, CARD, TRACK " +
            "(номер отправления/накладной, дословно), METER (показание счётчика — ТОЛЬКО цифры " +
            "показания, без единицы измерения), GEO (координаты точки — широта и долгота), PLACE (куда ехать, если адреса " +
            "нет: название отделения, магазина, населённого пункта — дословно с экрана), " +
            "AMOUNT (сумма к оплате или переводу — ТОЛЬКО цифры, без валюты), " +
            "RECEIPT (номер квитанции или чека, дословно), " +
            "SUBJECT (тема письма или сообщения; если это не письмо и не переписка — не пиши). ",
    )
    if (index != null) {
        append(
            "После значения укажи метки его слов в квадратных скобках: " +
                "TRACK=20 4514 9154 9395 [w3 w4 w5]. Если слова значения есть в списке — метки " +
                "ОБЯЗАТЕЛЬНЫ; текст без меток — только когда слов в списке нет. ",
        )
    }
    append(
        "Если ты не уверен, каких кандидатов на поле несколько — перечисли до " +
            "$MAX_FIELD_CANDIDATES строк с одним KEY, лучший первым; не выдумывай кандидатов " +
            "ради количества. " +
            "Дополнительно определи, ЧТО это за текст: если он целиком является " +
            "встречей/приглашением — строка TYPE=MEETING, покупкой/чеком/заказом — TYPE=PURCHASE, " +
            "кулинарным рецептом — TYPE=RECIPE, вакансией — TYPE=JOB; в остальных случаях строку " +
            "TYPE не пиши. Добавь строку SUMMARY=<суть текста в 3-6 словах>.\n\n",
    )
    append("2) Определи, кто играет каждую из ролей:\n")
    roles.forEach { append("- ").append(it.key).append(" — ").append(it.question).append('\n') }
    if (index != null) {
        append(
            "Отвечай строками вида роль=имя [метки слов имени]. Метки — из списка слов страницы; " +
                "слово-подпись (например «Відправник:», «Отримувач») в метки НЕ включай — " +
                "только слова самого имени. " +

                "Само имя пиши ПРАВИЛЬНО, исправляя явные искажения распознавания " +
                "(цифра вместо похожей буквы, потерянная буква): в списке слов может стоять " +
                "«1ваненко ван», а имя — «Іваненко Іван». Не выдумывай другое имя. " +
                "Роль, которой в документе нет, пропусти.\n\n",
        )
    } else {
        append(
            "Отвечай строками вида роль=идентификатор. Идентификатор — РОВНО один из " +
                "перечисленных выше, а не текст элемента. Роль, которой в документе нет, пропусти.\n\n",
        )
    }
    append("Без пояснений. Если не нашлось вообще ничего — ответь ровно NONE.\n")
}

internal fun parseFieldCandidates(answer: String): ParsedUnderstanding {
    val fields = LinkedHashMap<String, MutableList<FieldCandidate>>()
    val single = LinkedHashMap<String, String>()
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim().uppercase()
        val rest = line.substring(eq + 1).trim()
        if (rest.isEmpty()) return@forEach
        when {
            key == "TYPE" -> rest.lowercase().takeIf { it in KNOWN_SEMANTIC_TAGS }
                ?.let { single.putIfAbsent(META_SEMANTIC_TYPE, it) }
            key == "SUMMARY" -> single.putIfAbsent(META_SEMANTIC_SUMMARY, rest.take(120))
            else -> CONTRACT_KEYS[key]?.let { suffix ->
                val metaKey = META_ENTITY_PREFIX + suffix
                val candidate = splitCandidate(rest) ?: return@forEach
                val bucket = fields.getOrPut(metaKey) { mutableListOf() }
                if (bucket.size < MAX_FIELD_CANDIDATES && bucket.none { it.text == candidate.text && it.ids == candidate.ids }) {
                    bucket += candidate
                }
            }
        }
    }
    return ParsedUnderstanding(fields, single)
}

internal data class ParsedUnderstanding(
    val fields: Map<String, List<FieldCandidate>>,
    val single: Map<String, String>,
)

private fun splitCandidate(rest: String): FieldCandidate? {
    val m = TRAILING_IDS.find(rest)
    if (m != null) {
        val ids = m.groupValues[2].split(',')
            .flatMap { part -> part.trim().split(WHITESPACE) }
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("rule=") }
            .map(::bareIndexId)
        if (ids.isNotEmpty() && ids.all { ID_SHAPED.matches(it) }) {
            val text = m.groupValues[1].trim()
            return if (text.isEmpty()) null else FieldCandidate(text, ids)
        }
    }
    return FieldCandidate(rest)
}

private val TRAILING_IDS = Regex("""^(.*?)\s*\[([^\[\]]+)]$""")
private val ID_SHAPED = Regex("""[A-Za-z]+\d+""")
private val WHITESPACE = Regex("""\s+""")

class UnderstandCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "ai"
    override val meta = CapabilityMeta(priority = 31, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = labelNeedingKey("Понять", keys.keySet())
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.TEXT || state.has(Feature.HAS_TEXT)
    override fun produces(state: ObjectState) = state

    override fun yields(state: ObjectState) = ActionYield.Same
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object { val ID = CapabilityId("understand") }
}

class UnderstandRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = UnderstandCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = entitySourceText(input).take(MAX_CHARS)
                val elements = layoutOf(text)

                if (elements.isEmpty()) {
                    return@withContext if (input.state.kind == ObjectKind.IMAGE) {
                        readWithEyes(input)
                    } else {
                        ActionResult.Failure("Нет текста для понимания", recoverable = true)
                    }
                }
                val layer = atomLayer(input)
                val index = layer?.promptIndex()
                reportStage("Отправляю страницу модели")
                val answer = ask(input, understandPrompt(elements, index = index))
                reportStage("Проверяю прочитанное по странице")
                val parsed = parseFieldCandidates(answer)
                val judged = judgeFields(parsed.fields, layer)

                val retried = judged.retry.takeIf { it.isNotEmpty() }?.let { keys ->
                    reportStage("Контрольная цифра не сошлась — перечитываю")
                    val again = ask(input, retryPrompt(keys, elements, index))
                    judgeFields(parseFieldCandidates(again).fields.filterKeys { it in keys }, layer)
                }
                val fields = judged.won + retried?.won.orEmpty()

                val blocked = (judged.blocked.keys + retried?.blocked?.keys.orEmpty()).associateWith { key ->
                    (judged.blocked[key].orEmpty() + retried?.blocked?.get(key).orEmpty()).distinct()
                }

                val (roles, roleDisputes) = roleReadings(answer, elements, layer)
                if (fields.isEmpty() && parsed.single.isEmpty() && roles.isEmpty()) {

                    ActionResult.Done(NOTHING_NEW)
                } else {

                    val values = fields.mapValues { it.value.text } + parsed.single + roles
                    val merged = mergeFacts(input.metadata, values)

                    val roleAlts = roleDisputes
                        .filterKeys { key -> normConsensus(merged[key].orEmpty()) == normConsensus(roles[key].orEmpty()) }
                        .map { (key, readings) ->
                            key + META_ALT_SUFFIX to altValue((listOfNotNull(merged[key]) + readings).distinct())
                        }

                    val roleSources = roles.keys
                        .filter { key -> !merged[key].isNullOrBlank() }
                        .filter { key -> Provenance.MODEL > provenanceOf(merged, key) }
                        .map { key -> key + META_SOURCE_SUFFIX to Provenance.MODEL.wire }
                    ActionResult.Success(
                        ResultObject(
                            input.state.kind, input.mime, input.uri,
                            metadata = merged +
                                annotations(merged, fields, judgedByLayer = layer != null, blocked = blocked) +
                                roleAlts + roleSources +
                                ("op" to "understand"),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось понять документ", recoverable = true) }
        }

    private suspend fun readWithEyes(input: PointObject): ActionResult {
        reportStage("Смотрю на снимок")
        val answer = File(llm.run(input, VISUAL_PROMPT).uri.value).readText()
        val parsed = parseFieldCandidates(answer)
        val judged = judgeFields(parsed.fields, layer = null)
        val fields = judged.won
        if (fields.isEmpty() && parsed.single.isEmpty()) {
            return ActionResult.Failure("На снимке ничего не разобрать", recoverable = true)
        }
        val values = fields.mapValues { it.value.text } + parsed.single
        val merged = mergeFacts(input.metadata, values)
        return ActionResult.Success(
            ResultObject(
                input.state.kind, input.mime, input.uri,
                metadata = merged +
                    annotations(merged, fields, judgedByLayer = false, blocked = judged.blocked) +
                    (META_READING_MODE to ReadingMode.HANDWRITTEN.name) +
                    ("op" to "understand"),
            ),
        )
    }

    private suspend fun ask(input: PointObject, prompt: String): String =
        File(llm.run(textOnly(input), prompt).uri.value).readText()

    private fun annotations(
        merged: Map<String, String>,
        fields: Map<String, JudgedField>,
        judgedByLayer: Boolean,
        blocked: Map<String, List<String>>,
    ): Map<String, String> = buildMap {
        fields.forEach { (key, field) ->
            val readings = (listOfNotNull(merged[key]) + alternativesOf(merged, key) + field.candidates).distinct()
            if (readings.size > 1) put(key + META_ALT_SUFFIX, altValue(readings))

            if (normConsensus(merged[key].orEmpty()) != normConsensus(field.text)) return@forEach
            if (judgedByLayer) {
                val existing = merged[key + META_EVIDENCE_SUFFIX]?.split(',')?.count { it.isNotBlank() } ?: 0
                if (field.evidence.size >= existing) {
                    put(key + META_EVIDENCE_SUFFIX, field.evidence.joinToString(",") { it.name.lowercase() })
                }
            }
            val source = if (field.grounded) Provenance.OCR else Provenance.MODEL
            if (source > provenanceOf(merged, key)) put(key + META_SOURCE_SUFFIX, source.wire)
        }
        blocked.forEach { (key, texts) ->
            if (texts.isNotEmpty()) put(key + META_BLOCKED_SUFFIX, altValue(texts))
        }
    }

    private fun textOnly(input: PointObject) =
        if (input.state.kind == ObjectKind.TEXT) input
        else input.copy(mime = "text/plain", metadata = input.metadata - META_OCR_TEXT_REF)

    private fun atomLayer(input: PointObject): AtomLayer? =
        input.metadata[META_OCR_ATOMS_REF]?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }

    private companion object {
        const val MAX_CHARS = 6_000

        const val NOTHING_NEW = "Point уже прочитал всё, что здесь есть"

        const val VISUAL_PROMPT =
            "Прочитай, что написано на снимке. Это может быть табло счётчика, рукописная " +
                "запись, фото документа под углом или бликом. " +
                "Отвечай ТОЛЬКО строками вида KEY=значение, по одной на строку. Разрешённые KEY: " +
                "METER (показание счётчика — ТОЛЬКО цифры показания, без единицы измерения), " +
                "TRACK (номер отправления), PHONE, EMAIL, URL, ADDRESS, DATE, CARD, " +
                "GEO (координаты), PLACE (куда ехать: название места дословно), " +
                "AMOUNT (сумма — ТОЛЬКО цифры, без валюты), RECEIPT (номер квитанции или чека). " +
                "Добавь строку SUMMARY=<что на снимке, 3-6 слов>. " +
                "Цифры читай ровно так, как видишь: не додумывай и не выравнивай под привычный " +
                "формат. Если цифра не видна — не пиши строку вовсе. " +
                "Если разобрать нечего — ответь ровно NONE."

        fun retryPrompt(keys: Set<String>, elements: List<LayoutElement>, index: String?): String {
            val names = keys.mapNotNull { key ->
                CONTRACT_KEYS.entries.firstOrNull { META_ENTITY_PREFIX + it.value == key }?.key
            }
            return understandPrompt(elements, index = index) +
                "\nКандидаты полей ${names.joinToString(", ")} не прошли проверку контрольной цифры. " +
                "Перечитай страницу и верни ТОЛЬКО эти поля заново, до $MAX_FIELD_CANDIDATES " +
                "кандидатов каждое, с метками слов. Если настоящего значения нет — не пиши строку.\n"
        }
    }
}

internal fun roleReadings(
    answer: String,
    elements: List<LayoutElement>,
    layer: AtomLayer?,
): Pair<Map<String, String>, Map<String, List<String>>> {
    val fromElements = parseClassification(answer, elements)
        .associate { META_GRAPH_ROLE_PREFIX + it.role.key to it.element.text }
    if (layer == null) return fromElements to emptyMap()

    val byKey = CLASSIFIER_ROLES.associateBy { it.key }
    val values = LinkedHashMap<String, String>()
    val disputes = LinkedHashMap<String, List<String>>()
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val role = byKey[line.substring(0, eq).trim().lowercase()] ?: return@forEach
        val metaKey = META_GRAPH_ROLE_PREFIX + role.key
        if (metaKey in values) return@forEach
        val candidate = splitCandidate(line.substring(eq + 1).trim()) ?: return@forEach
        if (candidate.ids.isEmpty()) return@forEach

        val idsByAtom = layer.atoms.associateBy { it.id }
        val pointed = candidate.ids.map(::bareIndexId)
        val withoutLabel = pointed.filterNot { id ->
            idsByAtom[id]?.text?.let { role.isRoleLabel(it) } == true
        }
        val resolved = layer.resolve(AtomAddress.ByIds(withoutLabel.ifEmpty { pointed }))
        if (resolved.atoms.isEmpty()) return@forEach
        val page = resolved.text
        val model = candidate.text
        values[metaKey] = when {
            normConsensus(model) == normConsensus(page) -> page
            isRepairOf(page, model) -> model
            else -> {
                disputes[metaKey] = listOf(page, model)
                page
            }
        }
    }

    fromElements.forEach { (key, text) -> values.putIfAbsent(key, text) }
    return values to disputes
}

internal data class JudgedField(
    val text: String,
    val evidence: Set<EvidenceClass>,
    val grounded: Boolean,
    val candidates: List<String>,
)

internal data class JudgedFields(
    val won: Map<String, JudgedField>,
    val retry: Set<String>,

    val blocked: Map<String, List<String>> = emptyMap(),
)

internal fun judgeFields(
    fields: Map<String, List<FieldCandidate>>,
    layer: AtomLayer?,
): JudgedFields {
    val ruleMarks = layer?.ruleEvidence().orEmpty()
    val won = LinkedHashMap<String, JudgedField>()
    val retry = mutableSetOf<String>()
    val blockedByKey = LinkedHashMap<String, List<String>>()
    fields.forEach { (key, rawCandidates) ->
        val grounded = rawCandidates.flatMap { groundCandidate(key, it, layer) }
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
        )
    }
    return JudgedFields(won, retry, blockedByKey)
}

private fun groundCandidate(
    key: String,
    candidate: FieldCandidate,
    layer: AtomLayer?,
): List<Pair<FieldCandidate, Boolean>> {
    if (layer == null || candidate.ids.isEmpty()) return listOf(candidate.copy(ids = emptyList()) to false)
    val resolved = layer.resolve(AtomAddress.ByIds(candidate.ids.map(::bareIndexId)))
    if (resolved.atoms.isEmpty()) return listOf(candidate.copy(ids = emptyList()) to false)
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
