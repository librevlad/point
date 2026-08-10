package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

fun derivedYield(capability: Capability, state: ObjectState): ActionYield {
    val next = capability.produces(state)
    return when {
        next == null -> ActionYield.Unknown
        next === state -> ActionYield.None
        else -> ActionYield.New(next.kind)
    }
}

/**
 * Подпись под дверью действия. Негодный объект (#684/#685) договаривает причину сразу здесь,
 * вместо обычного «что вернёт», — дверь при этом никуда не девается, только у обещания
 * меняются слова.
 */
/**
 * `null` — второй строки нет вовсе (#629): у «Сохранить», «Поделиться», «Напечатать» имя уже
 * сказало всё, а одинаковая подпись под шестью действиями подряд ничего не добавляла.
 * Подпись остаётся там, где ей есть что добавить: что вернётся, куда уйдёт, почему нельзя.
 */
fun yieldLabel(yields: ActionYield, intent: Intent, unusableReason: String? = null): String? {
    if (unusableReason != null) return unusableReason
    return when (yields) {
        is ActionYield.New -> "вернёт " + (yields.noun ?: yieldNoun(yields.kind))

        ActionYield.Same -> "найдёт суть, суммы, даты и контакты"
        ActionYield.Unknown -> "вернёт то, что попросите"

        // «Копировать» и так говорит, что произойдёт.
        ActionYield.Copied -> null

        ActionYield.None -> when (intent) {
            Intent.OPEN -> "откроет в другом приложении"
            Intent.UNDERSTAND -> "покажет здесь же"
            else -> null
        }
    }
}

const val KEY_NOTE: String = "нужен ключ"

fun labelNeedingKey(label: String, keySet: Boolean): String =
    if (keySet) label else "$label · $KEY_NOTE"

private const val KEY_NOTE_SUFFIX = " · $KEY_NOTE"

fun labelNeedsKey(label: String): Boolean = label.endsWith(KEY_NOTE_SUFFIX)

fun labelWithoutKeyNote(label: String): String = label.removeSuffix(KEY_NOTE_SUFFIX)

fun keyErrandWhy(action: String): String =
    "«$action» ждёт ключа: это действие делает модель, а она работает на вашем ключе и вашей " +
        "квоте. У большинства сервисов ключ бесплатный. Три шага ниже — и вы вернётесь к своему " +
        "объекту."

fun yieldNoun(kind: ObjectKind): String = when (kind) {
    ObjectKind.TEXT -> "текст"
    ObjectKind.IMAGE -> "картинку"
    ObjectKind.PDF -> "PDF"
    ObjectKind.OFFICE -> "документ"
    ObjectKind.URL -> "ссылку"
    ObjectKind.AUDIO -> "запись"
    ObjectKind.ZIP -> "архив"
    ObjectKind.COLLECTION -> "набор файлов"
    else -> "файл"
}

const val META_YIELD_NOUN = "yield.noun"

fun yieldSurprise(expected: ActionYield, actual: ObjectKind, actualNoun: String? = null): String? {
    if (expected !is ActionYield.New) return null
    if (expected.kind != actual) return "Ожидался ${yieldNoun(expected.kind)} — вышел ${yieldNoun(actual)}"
    val promised = substanceOf(expected.noun) ?: return null
    val got = substanceOf(actualNoun) ?: return null
    return if (promised == got) null else "Обещали $promised — вышло $got"
}

private fun substanceOf(noun: String?): String? =
    noun?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() }

data class CapabilityEntry(
    val id: CapabilityId,
    val label: String,
    val accepts: List<ObjectKind>,
    val yields: List<ActionYield>,

    val declaredOnly: Boolean,
    val intents: Set<Intent>,
    val meta: CapabilityMeta,
) {

    val network: Boolean get() = meta.network
    val paid: Boolean get() = meta.cost == Cost.PAID
    val auth: Boolean get() = meta.auth
}

fun inventoryProbes(kinds: List<ObjectKind> = ObjectKind.entries): List<ObjectState> = kinds.flatMap { kind ->
    listOf(ObjectState(kind), ObjectState(kind, Feature.entries.toSet())) +
        Feature.entries.map { ObjectState(kind, setOf(it)) }
}

fun capabilityInventory(
    capabilities: Collection<Capability>,
    probes: List<ObjectState> = inventoryProbes(),
): List<CapabilityEntry> = capabilities
    .sortedWith(compareBy({ it.meta.priority }, { it.id.value }))
    .map { c ->
        val accepted = probes.filter { c.accepts(it) }
        CapabilityEntry(
            id = c.id,
            label = accepted.firstOrNull()?.let(c::label) ?: c.label(ObjectState(ObjectKind.UNKNOWN)),
            accepts = accepted.map { it.kind }.distinct(),
            yields = accepted.map { c.yields(it) }.distinct(),
            declaredOnly = accepted.any { c.yields(it) != derivedYield(c, it) },
            intents = accepted.flatMap { c.intents(it) }.toSet(),
            meta = c.meta,
        )
    }
