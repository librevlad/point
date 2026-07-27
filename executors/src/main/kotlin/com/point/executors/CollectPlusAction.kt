package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Contract categories → Russian section titles, in display order. */
private val COLLECT_CATEGORIES = linkedMapOf(
    "NAME" to "Имена",
    "ORG" to "Организации",
    "PHONE" to "Телефоны",
    "EMAIL" to "Почты",
    "URL" to "Ссылки",
    "ADDRESS" to "Адреса",
    "DATE" to "Даты",
    "AMOUNT" to "Суммы",
    "PRODUCT" to "Товары",
)

internal const val COLLECT_PLUS_PROMPT =
    "Собери из текста ВСЕ полезные данные и сгруппируй. Отвечай ТОЛЬКО строками вида КАТЕГОРИЯ=значение, " +
        "по одной на строку, без пояснений. Разрешённые категории: NAME (люди), ORG (организации), " +
        "PHONE, EMAIL, URL, ADDRESS, DATE, AMOUNT (суммы/числа с единицами), PRODUCT (товары/услуги). " +
        "Извлекай значения ДОСЛОВНО, ничего не выдумывай. Если ничего нет — ответь ровно NONE.\n\nТекст:\n"

/** Parse the strict contract into ordered, deduped category → values. */
internal fun parseCollected(answer: String): Map<String, List<String>> {
    val acc = linkedMapOf<String, MutableList<String>>()
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val cat = line.substring(0, eq).trim().uppercase()
        if (cat !in COLLECT_CATEGORIES) return@forEach
        val value = line.substring(eq + 1).trim()
        if (value.isEmpty()) return@forEach
        val bucket = acc.getOrPut(cat) { mutableListOf() }
        if (value !in bucket) bucket += value
    }
    return acc
}

/** Group the categories into a clean titled list, in [COLLECT_CATEGORIES] order. */
internal fun formatCollected(grouped: Map<String, List<String>>): String = buildString {
    COLLECT_CATEGORIES.forEach { (cat, title) ->
        val items = grouped[cat].orEmpty()
        if (items.isEmpty()) return@forEach
        if (isNotEmpty()) append("\n")
        append(title).append(":\n")
        items.forEach { append(it).append("\n") }
    }
}.trim()

/**
 * «Собрать данные+» (#128): the AI twin of «Собрать данные». The local twin collects what
 * ML Kit's patterns catch (phones/emails/links/addresses); this one asks the LLM to pull
 * the richer categories a regex can't — names, organizations, amounts, dates — via a strict
 * line contract, and groups them into the same clean shareable list.
 */
class CollectPlusCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "list"
    override val meta = CapabilityMeta(priority = 31, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "Собрать данные+"
    override fun accepts(state: ObjectState) =
        state.has(Feature.HAS_PHONE) || state.has(Feature.HAS_EMAIL) ||
            state.has(Feature.HAS_URL) || state.has(Feature.HAS_ADDRESS) || state.has(Feature.HAS_TEXT)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("collect-plus") }
}

class CollectPlusRealizer @Inject constructor(
    private val store: ObjectStore,
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = CollectPlusCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = entitySourceText(input).take(MAX_TEXT)
                if (text.isBlank()) return@withContext ActionResult.Failure("Нет текста для сбора", recoverable = true)
                val answer = llm.run(textStandIn(input), COLLECT_PLUS_PROMPT + text)
                val list = formatCollected(parseCollected(File(answer.uri.value).readText()))
                if (list.isBlank()) {
                    ActionResult.Failure("Ничего не удалось собрать", recoverable = true)
                } else {
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(list)
                    ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ref, mapOf("op" to "collect-plus")))
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось собрать данные", recoverable = true) }
        }

    /** The LLM judges the TEXT, not an image binary. */
    private fun textStandIn(input: PointObject) =
        if (input.state.kind == ObjectKind.TEXT) input else input.copy(mime = "text/plain")

    private companion object {
        const val MAX_TEXT = 20_000
    }
}
