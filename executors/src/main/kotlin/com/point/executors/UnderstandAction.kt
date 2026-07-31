package com.point.executors

import com.point.core.flow.CLASSIFIER_ROLES
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ClassifierRole
import com.point.core.flow.Cost
import com.point.core.flow.KNOWN_SEMANTIC_TAGS
import com.point.core.flow.Latency
import com.point.core.flow.LayoutElement
import com.point.core.flow.LlmClient
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.Realizer
import com.point.core.flow.layoutOf
import com.point.core.flow.mergeFacts
import com.point.core.flow.parseClassification
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
 * «Понять» — три AI-кнопки, свёрнутые в одно действие (#260, design v3 «Порядок работ» п.5).
 *
 * «Понять глубже» (модель ищет сущности), «Кто есть кто» (модель расставляет роли) и «Собрать
 * данные+» (показать найденное списком) были стадиями нашего пайплайна, вылезшими в UI, — то
 * самое, что #26 закрывал ещё год назад. Пользователь выбирает намерение «понять документ»,
 * а не стадию конвейера.
 *
 * Свёртка буквальна, а не косметична: оба построчных контракта совместимы по построению —
 * один вызов модели, один ответ, и по нему проходят оба готовых парсера. [parseUnderstanding]
 * не знает ролей и отбрасывает `sender=P4`; [parseClassification] не знает фактов и
 * отбрасывает `PHONE=…`. «Показать найденное» перестаёт быть кнопкой: найденное показывает
 * сам экран объекта — чеклист «Point понял» и готовность действий ([actionReadiness]).
 *
 * Прежние правила все живы: никогда не автоматически (явный тап, согласие на облако #10);
 * в облако уходит только распознанный текст, не пиксели; значения — дословно, цифры
 * неприкосновенны; роль пишется текстом элемента страницы, а не формулировкой модели;
 * находки сливаются голосованием ([mergeFacts]), а не поверх.
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
 * Один запрос обеих стадий: элементы с идентификаторами, контракт значений, контракт ролей.
 *
 * Note what is absent (гарантия структурная, как у классификатора #222): no object ids,
 * no kinds, no relations, no earlier findings — the graph has no route into this string.
 */
internal fun understandPrompt(
    elements: List<LayoutElement>,
    roles: List<ClassifierRole> = CLASSIFIER_ROLES,
): String = buildString {
    append("Текст распознан с фотографии и может содержать ошибки распознавания. ")
    append("Ниже его элементы, у каждого свой идентификатор.\n\n")
    elements.forEach { append(it.id).append(": ").append(it.text).append('\n') }
    append("\nСделай две вещи.\n\n")
    append(
        "1) Найди контактные данные и номера. Значение приводи ПОЛНОСТЬЮ, как оно есть в " +
            "документе (адрес — вместе с населённым пунктом), и исправляй только явные искажения " +
            "распознавания. НИЧЕГО не додумывай: если чего-то в тексте нет — не пиши строку. " +
            "Цифры не меняй. Отвечай строками вида KEY=значение, по одной на строку. " +
            "Разрешённые KEY: PHONE, EMAIL, URL, ADDRESS, DATE, CARD, TRACK " +
            "(номер отправления/накладной, дословно). " +
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

/** Parse the strict value contract: known keys only, first value per key wins, blanks dropped.
 *  The semantic level (#89) rides the same contract: TYPE (whitelisted) and SUMMARY. */
internal fun parseUnderstanding(answer: String): Map<String, String> = buildMap {
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim().uppercase()
        val value = line.substring(eq + 1).trim()
        if (value.isEmpty()) return@forEach
        when {
            // Любой известный тег — из закрытой карты признаков или из карты документов;
            // выдуманный моделью отбрасывается (#222: никакого свободного текста).
            key == "TYPE" -> value.lowercase().takeIf { it in KNOWN_SEMANTIC_TAGS }
                ?.let { putIfAbsent(META_SEMANTIC_TYPE, it) }
            key == "SUMMARY" -> putIfAbsent(META_SEMANTIC_SUMMARY, value.take(120))
            else -> CONTRACT_KEYS[key]?.let { putIfAbsent(META_ENTITY_PREFIX + it, value) }
        }
    }
}

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
                val answer = File(llm.run(textOnly(input), understandPrompt(elements)).uri.value).readText()
                val facts = parseUnderstanding(answer)
                // Роль пишется собственным текстом элемента, не формулировкой модели; выдуманный
                // идентификатор отброшен парсером и не тратит свою роль (#222, шаг 6).
                val roles = parseClassification(answer, elements)
                    .associate { META_GRAPH_ROLE_PREFIX + it.role.key to it.element.text }
                if (facts.isEmpty() && roles.isEmpty()) {
                    ActionResult.Failure("Ничего нового не найдено", recoverable = true)
                } else {
                    // The same object, one understanding richer: same bytes, merged facts.
                    // #222, шаг 7: голосованием, а не поверх — расхождение с тем, что нашёл
                    // экстрактор на устройстве, записывается в `<key>.alt`, а при равенстве
                    // голосов побеждает уже известное: платная догадка не выигрывает тем,
                    // что пришла второй. Готовность действий пересчитается с новых фактов.
                    ActionResult.Success(
                        ResultObject(
                            input.state.kind, input.mime, input.uri,
                            metadata = mergeFacts(input.metadata, facts + roles) + ("op" to "understand"),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось понять документ", recoverable = true) }
        }

    /** The LLM must judge the TEXT, not re-read the image — a text-shaped stand-in object. */
    private fun textOnly(input: PointObject) =
        if (input.state.kind == ObjectKind.TEXT) input
        else input.copy(mime = "text/plain", metadata = input.metadata - META_OCR_TEXT_REF)

    private companion object {
        const val MAX_CHARS = 6_000
    }
}
