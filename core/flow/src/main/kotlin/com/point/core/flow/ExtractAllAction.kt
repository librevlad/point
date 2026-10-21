package com.point.core.flow

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

/**
 * Сводка того, что Point об объекте знает (#1422).
 *
 * Прежде сводка была своим списком из четырёх видов и вторым чтением мимо графа: даты и суммы,
 * уже найденные и показанные действиями на том же экране, в «данные» не попадали. Теперь
 * первым идёт знание объекта — каждый вид `entity.*`, у которого есть имя для человека, со
 * всеми его «ещё», — а чтение текста только добавляет то, чего граф не назвал.
 *
 * Подписи при знании (источник, спор, строка документа) — не данные и в сводку не идут.
 */
internal fun formatEntities(entities: List<Entity>, facts: Map<String, String> = emptyMap()): String {
    val found = linkedMapOf<String, MutableList<String>>()
    fun add(key: String, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        val list = found.getOrPut(key) { mutableListOf() }
        if (list.none { sameValue(it, clean) }) list += clean
    }

    facts.keys
        .filter { it.startsWith(META_ENTITY_PREFIX) && !isAnnotationKey(it) && understoodName(it) != null }
        .sortedBy { key -> SECTION_ORDER.indexOf(key).let { if (it < 0) SECTION_ORDER.size else it } }
        .forEach { key ->
            facts[key]?.let { add(key, it) }
            moreOf(facts, key).forEach { add(key, it) }
        }
    entities.forEach { entity -> entity.type.summaryKey()?.let { add(it, entity.value) } }

    return buildString {
        SECTION_ORDER.filter { it in found }.forEach { appendSection(sectionTitle(it), found.getValue(it)) }
        found.keys.filterNot { it in SECTION_ORDER }.forEach { appendSection(sectionTitle(it), found.getValue(it)) }
    }.trim()
}

private val SECTION_ORDER = listOf("phone", "email", "url", "address", "date", "amount", "card")
    .map { META_ENTITY_PREFIX + it }

private fun EntityType.summaryKey(): String? = asMetaKey() ?: if (this == EntityType.MONEY) META_ENTITY_AMOUNT else null

private fun sectionTitle(key: String): String = when (key.removePrefix(META_ENTITY_PREFIX)) {
    "phone" -> "Телефоны"
    "email" -> "Почты"
    "url" -> "Ссылки"
    "address" -> "Адреса"
    "date" -> "Даты"
    "amount" -> "Суммы"
    "card" -> "Карты"
    else -> understoodName(key) ?: key.removePrefix(META_ENTITY_PREFIX)
}

/** Одно значение, записанное по-разному: «+380 67 123 45 67» и «+380671234567». */
private fun sameValue(a: String, b: String): Boolean {
    fun core(s: String) = s.lowercase().filter(Char::isLetterOrDigit)
    return core(a).isNotEmpty() && core(a) == core(b)
}

private fun StringBuilder.appendSection(title: String, items: List<String>) {
    if (items.isEmpty()) return
    if (isNotEmpty()) append("\n")
    append(title).append(":\n")
    items.forEach { append(it).append("\n") }
}

class ExtractAllCapability : Capability {
    override val id = ID
    override val icon = "list"

    override val meta = CapabilityMeta(priority = 30, latency = Latency.FAST)
    override fun label(state: ObjectState) = "Собрать данные"

    // Собирать имеет смысл там, где есть ЧТО собирать: на узле-одиночке действие
    // возвращало копию самого себя новым объектом (#676, охота 2026-08-09).
    override fun accepts(state: ObjectState) =
        state.kind !in com.point.core.flow.EXTRACTED_KINDS &&
            listOf(Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_URL, Feature.HAS_ADDRESS)
                .count(state::has) >= 2
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("extract-all") }
}

class ExtractAllRealizer(
    private val store: ObjectStore,
    private val extractor: EntityExtractor,
) : Realizer {
    override val capabilityId = ExtractAllCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = com.point.core.flow.entitySourceText(input)
                val list = formatEntities(extractor.extract(text), input.metadata)
                if (list.isBlank()) {
                    ActionResult.Failure("Не удалось собрать данные", recoverable = true)
                } else {
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(list)
                    ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ref, mapOf("op" to "extract-all")))
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось собрать данные", recoverable = true) }
        }
}
