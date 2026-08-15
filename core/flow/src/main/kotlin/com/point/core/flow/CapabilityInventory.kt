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
 * Подпись под дверью действия — или её отсутствие.
 *
 * Подписи, выведенной из типа результата, больше нет (#582, решение владельца): «вернёт
 * текст», «откроет в другом приложении», «вернёт то, что попросите» — это пересказ механики
 * теми же словами, что и имя действия. Имя уже сказало, что произойдёт.
 *
 * Вторая строка остаётся там, где она сказана про этот конкретный случай:
 * - слово, написанное у самого действия (`ActionYield.New` со своим `noun`), — предупреждение
 *   или обещание по существу: «текст · сначала на телефоне, потом спрошу про сервис»,
 *   «PDF с текстом документа · без оформления»;
 * - подпись «Понять» (#580), названная руками, а не типом;
 * - причина негодности исходника (#684/#685): дверь не исчезает, но заранее говорит, почему
 *   с этим объектом не выйдет.
 *
 * Само обещание (`ActionYield`) при этом никуда не девается: на нём держится `yieldSurprise`
 * — честное «вышло другое». Убрана надпись на экране, а не знание о том, что действие обещает.
 */
fun yieldLabel(
    yields: ActionYield,
    unusableReason: String? = null,

    /**
     * Держится ли обещание на этом объекте (#994).
     *
     * Про негодный файл экран дважды сказал, что открыть его не вышло, — и тут же первым и
     * подсвеченным предлагал «Понять · найдёт суть, суммы, даты и контакты». Причина у всех
     * действий общая и потому сказана один раз (#874), а на её месте вставало обещание
     * результата, которого быть не может. Обещание, которое нельзя сдержать, не даётся вовсе:
     * дверь остаётся, слов при ней нет.
     */
    promiseHolds: Boolean = true,
): String? {
    if (unusableReason != null) return unusableReason
    if (!promiseHolds) return null
    return when (yields) {
        is ActionYield.New -> yields.noun

        // Обещание «Понять» (#580). «Исправить ошибки» обещает то же самое типом результата,
        // но делает другую работу — и на найденном человеке сулило «суммы и даты», которых
        // у человека не бывает (#771, живая охота 11.08.2026).
        // Слова принадлежат способности, а не типу исхода (#734): наследовать чужое
        // обещание молча больше нечему — здесь его просто нет.
        is ActionYield.Same -> yields.note

        ActionYield.Unknown, ActionYield.Copied, ActionYield.None -> null
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
