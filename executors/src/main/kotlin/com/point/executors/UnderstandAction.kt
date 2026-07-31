package com.point.executors

import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.CLASSIFIER_ROLES
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
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_EVIDENCE_SUFFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.Realizer
import com.point.core.flow.SOURCE_MODEL
import com.point.core.flow.SOURCE_OCR
import com.point.core.flow.AtomAddress
import com.point.core.flow.altValue
import com.point.core.flow.alternativesOf
import com.point.core.flow.bareIndexId
import com.point.core.flow.fieldEvidence
import com.point.core.flow.isRepairOf
import com.point.core.flow.layoutOf
import com.point.core.flow.mergeFacts
import com.point.core.flow.normConsensus
import com.point.core.flow.parseClassification
import com.point.core.flow.promptIndex
import com.point.core.flow.resolve
import com.point.core.flow.ruleEvidence
import com.point.core.flow.s10CheckDigitValid
import com.point.core.flow.semanticFits
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/*
 * «Понять» — три AI-кнопки, свёрнутые в одно действие (#260), теперь с кандидатами и уликами
 * в том же вызове (#261, design v3 §4/§7).
 *
 * Вопрос «этого действительно нет или ты пропустил?» — самоподтверждение, а не проверка.
 * Поэтому модель обязана вернуть до трёх кандидатов на поле — в ТОМ ЖЕ ответе, с метками слов
 * страницы, которыми она указала. Судит кандидатов код, не модель:
 *
 * - улики считаются по слою атомов ([fieldEvidence]) — форма, подпись рядом, связность;
 *   подтверждено = минимум два независимых класса, одно — предположение, и оно видно;
 * - правила размечают вход (`rule=` в индексе слов), никогда не отсеивают и не решают;
 * - hard-block — только математически невозможное: контрольная цифра S10 не сошлась там,
 *   где формат её поддерживает; второй вызов — один и только по отклонённым полям;
 * - у значения — происхождение: указано метками и собрано атомами → ПРОЧИТАНО (`ocr`),
 *   продиктовано без указания → ПРОЧИТАНО МОДЕЛЬЮ (`model`).
 *
 * Прежние правила все живы: никогда не автоматически; в облако — только текст; роль пишется
 * текстом элемента страницы; находки сливаются голосованием ([mergeFacts]), и правка человека
 * не переписывается прилетевшим позже ответом (#243 — порядок аргументов).
 */

/** Fixed contract keys → entity metadata suffixes. TRACK — #260: у идентификатора нет
 *  универсальной формы, поэтому номер отправления спрашивается у модели наравне с правилом. */
private val CONTRACT_KEYS = mapOf(
    "PHONE" to "phone",
    "EMAIL" to "email",
    "URL" to "url",
    "ADDRESS" to "address",
    "DATE" to "date",
    "CARD" to "card",
    "TRACK" to "track",
)

/**
 * Один запрос обеих стадий: элементы с идентификаторами, контракт значений (кандидаты с
 * метками при живом [index]), контракт ролей.
 *
 * Note what is absent (гарантия структурная, как у классификатора #222): no object ids,
 * no kinds, no relations, no earlier findings — the graph has no route into this string.
 */
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
            "(номер отправления/накладной, дословно). ",
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
    append("2) Определи, какой элемент играет каждую из ролей:\n")
    roles.forEach { append("- ").append(it.key).append(" — ").append(it.question).append('\n') }
    append(
        "Отвечай строками вида роль=идентификатор. Идентификатор — РОВНО один из " +
            "перечисленных выше, а не текст элемента. Роль, которой в документе нет, пропусти.\n\n",
    )
    append("Без пояснений. Если не нашлось вообще ничего — ответь ровно NONE.\n")
}

/**
 * Разбор контракта значений с кандидатами: `KEY=значение [w1 w2]`, до [MAX_FIELD_CANDIDATES]
 * строк на ключ в порядке ответа (лучший первым), пустые значения отброшены. Квадратные скобки
 * в конце — метки, только если каждый их кусок похож на метку индекса; иначе это часть текста
 * («Відділення №9 [нове]» — не указание). TYPE (по белому списку) и SUMMARY — одиночные.
 */
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

/** `значение [w1 w2]` → кандидат; скобки без меток остаются текстом. Пустой текст — не кандидат. */
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

/**
 * «Понять» — the one understanding bubble. Accepts any TEXT, or an IMAGE whose OCR sidecar
 * already exists (never a raw photo — only text ever leaves the device, and only after the
 * user taps and consents).
 */
class UnderstandCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ai"
    override val meta = CapabilityMeta(priority = 31, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Понять"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.TEXT || state.has(Feature.HAS_TEXT)
    override fun produces(state: ObjectState) = state
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
                    return@withContext ActionResult.Failure("Нет текста для понимания", recoverable = true)
                }
                val layer = atomLayer(input)
                val index = layer?.promptIndex()
                val answer = ask(input, understandPrompt(elements, index = index))
                val parsed = parseFieldCandidates(answer)
                val judged = judgeFields(parsed.fields, layer)
                // Второй вызов — один и только по полям, где валидатор отклонил ВСЕХ кандидатов
                // (design v3 §7): не «поищи ещё» всему документу — самоподтверждение, — а
                // конкретный конфликт конкретного поля.
                val retried = judged.retry.takeIf { it.isNotEmpty() }?.let { keys ->
                    val again = ask(input, retryPrompt(keys, elements, index))
                    judgeFields(parseFieldCandidates(again).fields.filterKeys { it in keys }, layer)
                }
                val fields = judged.won + retried?.won.orEmpty()
                // Роль — собственным текстом элемента, не формулировкой модели; выдуманный
                // идентификатор отброшен парсером и не тратит свою роль (#222, шаг 6).
                val roles = parseClassification(answer, elements)
                    .associate { META_GRAPH_ROLE_PREFIX + it.role.key to it.element.text }
                if (fields.isEmpty() && parsed.single.isEmpty() && roles.isEmpty()) {
                    ActionResult.Failure("Ничего нового не найдено", recoverable = true)
                } else {
                    // #222, шаг 7: голосованием, а не поверх — известное не затирается, спор
                    // виден в .alt, платная догадка не выигрывает тем, что пришла второй.
                    val values = fields.mapValues { it.value.text } + parsed.single + roles
                    val merged = mergeFacts(input.metadata, values)
                    ActionResult.Success(
                        ResultObject(
                            input.state.kind, input.mime, input.uri,
                            metadata = merged + annotations(merged, fields) + ("op" to "understand"),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось понять документ", recoverable = true) }
        }

    private suspend fun ask(input: PointObject, prompt: String): String =
        File(llm.run(textOnly(input), prompt).uri.value).readText()

    /**
     * Аннотации к слитым фактам: улики и происхождение — только тем ключам, где голосование
     * оставило именно наше чтение (иначе аннотация приписала бы чужому значению наши улики);
     * кандидаты — в `.alt` рядом с победителем, ничего не выброшено молча.
     */
    private fun annotations(merged: Map<String, String>, fields: Map<String, JudgedField>): Map<String, String> =
        buildMap {
            fields.forEach { (key, field) ->
                if (normConsensus(merged[key].orEmpty()) != normConsensus(field.text)) return@forEach
                put(key + META_SOURCE_SUFFIX, if (field.grounded) SOURCE_OCR else SOURCE_MODEL)
                put(key + META_EVIDENCE_SUFFIX, field.evidence.joinToString(",") { it.name.lowercase() })
                val readings = (alternativesOf(merged, key) + field.candidates).distinct()
                if (readings.size > 1) put(key + META_ALT_SUFFIX, altValue(readings))
            }
        }

    /** The LLM must judge the TEXT, not re-read the image — a text-shaped stand-in object. */
    private fun textOnly(input: PointObject) =
        if (input.state.kind == ObjectKind.TEXT) input
        else input.copy(mime = "text/plain", metadata = input.metadata - META_OCR_TEXT_REF)

    /** Слой слов страницы, если распознавание его уже сложило; битый дамп не роняет действие. */
    private fun atomLayer(input: PointObject): AtomLayer? =
        input.metadata[META_OCR_ATOMS_REF]?.let { ref ->
            runCatching { AtomCodec.decode(File(ref).readText()) }.getOrNull()
        }

    private companion object {
        const val MAX_CHARS = 6_000

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

/** Победивший кандидат поля после суда: текст, улики, происхождение и все выжившие чтения. */
internal data class JudgedField(
    val text: String,
    val evidence: Set<EvidenceClass>,
    val grounded: Boolean,
    val candidates: List<String>,
)

internal data class JudgedFields(val won: Map<String, JudgedField>, val retry: Set<String>)

/**
 * Суд над кандидатами поля — кодом, не моделью (#261):
 *
 * 1. **Заземление.** Кандидат с метками читается из атомов ([resolve]): текст значения — текст
 *    страницы, модельное чтение судится готовыми правилами (совпало по [normConsensus] —
 *    забыто; починило буквы — [isRepairOf]; тронуло цифру — остаётся ОТДЕЛЬНЫМ кандидатом-
 *    диктовкой, спор виден). Галлюцинированные метки — кандидат живёт диктовкой, без улик
 *    структуры.
 * 2. **Hard-block.** Только математически невозможное: S10-checksum не сошлась ([s10CheckDigitValid]
 *    `== false`). Отклонены все кандидаты поля — поле уходит в [JudgedFields.retry].
 * 3. **Победитель** — больше независимых классов улик ([fieldEvidence]); ничья — первый
 *    (порядок модели = её предпочтение). Проигравшие не выбрасываются: их тексты вернутся
 *    рядом с победителем в `.alt`.
 */
internal fun judgeFields(
    fields: Map<String, List<FieldCandidate>>,
    layer: AtomLayer?,
): JudgedFields {
    val ruleMarks = layer?.ruleEvidence().orEmpty()
    val won = LinkedHashMap<String, JudgedField>()
    val retry = mutableSetOf<String>()
    fields.forEach { (key, rawCandidates) ->
        val grounded = rawCandidates.flatMap { groundCandidate(key, it, layer) }
            .distinctBy { it.first.text to it.first.ids }
        val alive = grounded.filterNot { (c, _) -> s10CheckDigitValid(c.text) == false }
        if (alive.isEmpty()) {
            if (grounded.isNotEmpty()) retry += key
            return@forEach
        }
        val scored = alive.map { (c, isGrounded) ->
            val evidence = layer?.fieldEvidence(key, c, ruleMarks)
                ?: setOfNotNull(EvidenceClass.SEMANTIC.takeIf { semanticFits(key, c.text) == true })
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
    return JudgedFields(won, retry)
}

/**
 * Заземление кандидата: метки настоящие → текст со страницы; модельное чтение, тронувшее
 * цифру, — не потеря и не ремонт, а второй кандидат-диктовка (спор виден, ничего не глотается).
 *
 * Гвард противоречия: если собранный со страницы текст **проваливает** форму поля, а диктовка
 * модели её проходит — указание противоречит заявлению (модель ткнула не в те слова), и
 * огрызок страницы не становится кандидатом: иначе позиционные улики огрызка перевесили бы
 * настоящее значение.
 */
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
