package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.KNOWN_SEMANTIC_TAGS
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/*
 * The structured LLM fallback (#64, the last slice). Never automatic: an explicit bubble,
 * network-gated behind the one-time cloud consent (#10). The LLM answers a STRICT line
 * contract (KEY=VALUE, fixed keys, NONE when empty) — detection by format, never by prose
 * matching. Findings merge into the object's entity.* metadata; the metadata enricher then
 * lights the features, so «Позвонить»/«Создать событие» appear for what ML Kit missed.
 */

/** Fixed contract keys → entity metadata suffixes. */
private val CONTRACT_KEYS = mapOf(
    "PHONE" to "phone",
    "EMAIL" to "email",
    "URL" to "url",
    "ADDRESS" to "address",
    "DATE" to "date",
    "CARD" to "card",
)

internal const val DEEP_UNDERSTAND_PROMPT =
    "Найди в тексте контактные данные и извлеки их ДОСЛОВНО. Отвечай ТОЛЬКО строками вида " +
        "KEY=значение, по одной на строку, без пояснений. Разрешённые KEY: PHONE, EMAIL, URL, " +
        "ADDRESS, DATE, CARD. Дополнительно определи, ЧТО это за текст: если он целиком является " +
        "встречей/приглашением — строка TYPE=MEETING, покупкой/чеком/заказом — TYPE=PURCHASE, " +
        "кулинарным рецептом — TYPE=RECIPE, вакансией — TYPE=JOB; в остальных случаях строку TYPE " +
        "не пиши. Добавь строку SUMMARY=<суть текста в 3-6 словах>. " +
        "Если ничего нет — ответь ровно NONE.\n\nТекст:\n"

/** Parse the strict contract: known keys only, first value per key wins, blanks dropped.
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
 * «Понять глубже» — the cloud reserve for what on-device extraction missed. Accepts any
 * TEXT, or an IMAGE whose OCR sidecar already exists (never a raw photo — only text ever
 * leaves the device, and only after the user taps and consents).
 */
class DeepUnderstandCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "ai"
    override val meta = CapabilityMeta(priority = 34, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Понять глубже"
    override fun accepts(state: ObjectState) =
        state.kind == com.point.core.model.ObjectKind.TEXT || state.has(com.point.core.model.Feature.HAS_TEXT)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object { val ID = CapabilityId("deep-understand") }
}

class DeepUnderstandRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = DeepUnderstandCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = entitySourceText(input).take(MAX_CHARS)
                if (text.isBlank()) return@withContext ActionResult.Failure("Нет текста для понимания", recoverable = true)
                val answer = llm.run(textOnly(input, text), DEEP_UNDERSTAND_PROMPT + text)
                val found = parseUnderstanding(File(answer.uri.value).readText())
                if (found.isEmpty()) {
                    ActionResult.Failure("Ничего нового не найдено", recoverable = true)
                } else {
                    // The same object, one understanding richer: same bytes, merged facts.
                    // The metadata enricher lights the features on the next frame.
                    ActionResult.Success(
                        ResultObject(
                            input.state.kind, input.mime, input.uri,
                            metadata = input.metadata + found + ("op" to "deep-understand"),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось понять глубже", recoverable = true) }
        }

    /** The LLM must judge the TEXT, not re-read the image — a text-shaped stand-in object. */
    private fun textOnly(input: PointObject, text: String) =
        if (input.state.kind == com.point.core.model.ObjectKind.TEXT) input
        else input.copy(mime = "text/plain", metadata = input.metadata - META_OCR_TEXT_REF)

    private companion object {
        const val MAX_CHARS = 6_000
    }
}
