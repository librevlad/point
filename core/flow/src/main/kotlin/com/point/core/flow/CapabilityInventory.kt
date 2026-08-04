package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

/**
 * Инвентаризация способностей (#491): кто что принимает и что возвращает.
 *
 * Владелец, дословно: «инвентаризовать кто что умеет принимать и возвращать и показывать это
 * пользователю». Таблица **выводится из реестра** и никогда не пишется руками — `Capability` и
 * есть декларация «что можно», и написанный отдельно список разошёлся бы с правдой на первой же
 * новой способности. Ровно тот же довод, по которому Flow Graph выводится, а не хранится.
 *
 * Здесь только чистые функции: опрос деклараций и слова, которыми ответ показывают человеку.
 * Реализация (`Realizer`) сюда не приходит — таблица строится по тому, что видит UI.
 */

/**
 * Что способность вернула бы при [state], если бы её никто не переопределял, — умолчание
 * [Capability.yields], вынесенное отдельной функцией.
 *
 * Отдельно оно нужно ровно затем, чтобы **посчитать расхождения**: способность, у которой
 * `yields` разошёлся с этим выводом, — это место, где одного `produces` не хватило, чтобы
 * сказать правду. Их число и есть ответ на «у скольких `produces` расходится с реальностью».
 */
fun derivedYield(capability: Capability, state: ObjectState): ActionYield {
    val next = capability.produces(state)
    return when {
        next == null -> ActionYield.Unknown
        next === state -> ActionYield.None
        else -> ActionYield.New(next.kind)
    }
}

/**
 * Строка под названием действия: «вернёт текст», «вернёт таблицу», «ничего не вернёт — отправит».
 *
 * Слова владельца из #491, и намеренно **ожидание**, а не обещание: истинное состояние
 * переклассифицируется из реального выхода, поэтому строка говорит, чего ждать, а разошедшийся
 * исход досказывает [yieldSurprise].
 *
 * [intent] нужен только терминальным: «ничего не вернёт» без продолжения звучит как поломка, а
 * продолжений три — отправит, откроет, покажет.
 */
fun yieldLabel(yields: ActionYield, intent: Intent): String = when (yields) {
    is ActionYield.New -> "вернёт " + (yields.noun ?: yieldNoun(yields.kind))
    ActionYield.Same -> "объект тот же — Point поймёт больше"
    ActionYield.Unknown -> "вернёт то, что попросите"
    // Терминальное действие говорит, ЧТО сделает, а не чего не сделает. Прежняя формулировка
    // начиналась с отрицания («ничего не вернёт — отправит»), и на экране объекта два таких
    // подряд читались как поломка: глаз ловит «ничего не» раньше глагола. Дизайн-ревью
    // 04.08.2026 на живом экране — из шести строк четыре начинались с «вернёт», две с «не».
    ActionYield.None -> when (intent) {
        Intent.OPEN -> "откроет в другом приложении"
        Intent.UNDERSTAND -> "покажет здесь же"
        else -> "отправит и вернётся сюда"
    }
}

/** Как называется то, что вернётся, в винительном падеже: «вернёт **текст**». */
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

/**
 * Вышло не то, что было обещано, — и это сказано вслух (#491).
 *
 * `produces` объявлен подсказкой, а не истиной: настоящий вид переклассифицируется из реального
 * выхода. До этого среза расхождение просто пропадало — человек читал «вернёт текст», получал
 * таблицу и никакого объяснения. Молчать здесь хуже, чем ошибиться: ошибка видна и понятна,
 * молчание выглядит как «оно сделало что-то не то».
 *
 * `null` — расхождения нет либо судить не о чем ([ActionYield.Unknown] у AI ничего не обещал,
 * [ActionYield.Same] и [ActionYield.None] судятся не видом, а наличием объекта).
 */
fun yieldSurprise(expected: ActionYield, actual: ObjectKind): String? {
    if (expected !is ActionYield.New) return null
    if (expected.kind == actual) return null
    return "Ожидался ${yieldNoun(expected.kind)} — вышел ${yieldNoun(actual)}"
}

/**
 * Одна строка инвентаризации: что способность объявляет о себе.
 *
 * [accepts] — виды, на которых она предлагает себя хоть в одном опрошенном состоянии;
 * [yields] — что при этом обещает вернуть (различное, в порядке видов). Одна способность
 * законно возвращает разное на разном входе: «В PDF» с картинки делает PDF, а с PDF — текст.
 */
data class CapabilityEntry(
    val id: CapabilityId,
    val label: String,
    val accepts: List<ObjectKind>,
    val yields: List<ActionYield>,
    /** Способность сказала о своём выходе сама, потому что по одному `produces` вышла бы
     *  неправда (#491). Считается, а не проставляется: это [derivedYield] против объявленного. */
    val declaredOnly: Boolean,
    val intents: Set<Intent>,
    val meta: CapabilityMeta,
) {
    /** Уходит ли объект с устройства по объявлению. Правду о цепочке реализаторов знает
     *  `Resolver.leavesDevice` — здесь именно **объявление**, то, что видит UI. */
    val network: Boolean get() = meta.network
    val paid: Boolean get() = meta.cost == Cost.PAID
    val auth: Boolean get() = meta.auth
}

/**
 * Состояния, которыми опрашивается реестр: каждый вид голым, при полном понимании и с каждым
 * признаком по одному.
 *
 * Первые два очевидны: голое состояние — то, что известно на первом экране без единого чтения с
 * диска, полное — потолок, до которого объект дорастает обогащением.
 *
 * **Третье пришлось добавить по находке этого же среза.** Двух проб не хватало: гейт бывает
 * ОТРИЦАТЕЛЬНЫМ. «Сохранить контакт» принимает `!HAS_VCARD && (HAS_PHONE || HAS_EMAIL)` — то есть
 * номер, прочитанный на скриншоте, но не присланную визитку, у которой своё действие. При полном
 * наборе признаков `HAS_VCARD` тоже горит, и способность честно отказывалась; в таблице она
 * выглядела мёртвой, хотя человеку показывается каждый день. Ровно та ошибка, которую написанный
 * от руки список сделал бы и не заметил.
 *
 * Способность, не принявшая ни одной пробы, не предлагается человеку никогда — и это находка, а не
 * шум.
 */
fun inventoryProbes(): List<ObjectState> = ObjectKind.entries.flatMap { kind ->
    listOf(ObjectState(kind), ObjectState(kind, Feature.entries.toSet())) +
        Feature.entries.map { ObjectState(kind, setOf(it)) }
}

/**
 * Собрать инвентаризацию по набору способностей — тому самому, из которого выводится Flow Graph.
 *
 * Порядок — по `meta.priority`, затем по id: тот же детерминированный порядок, что у пузырьков
 * без обучения, чтобы таблицу можно было сверять глазами от прогона к прогону.
 */
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
