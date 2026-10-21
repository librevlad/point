package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState

import com.point.core.model.tr
/**
 * Кого Resolver позовёт первым для этого действия и этого объекта — или `null`, если некого
 * или устройство об этом не спрашивает (#1413).
 *
 * Нужен экрану ради слова о цене: оно — свойство выбранного исполнителя, а не самой
 * способности. Способность объявляет обещание один раз, а исполнителя выбирает Resolver — по
 * режиму, согласию и тому, кто сейчас рядом.
 */
fun interface ChosenExecutor {
    fun chosenFor(capabilityId: CapabilityId, state: ObjectState): RealizerMeta?
}

/** Части обещания, которые называют здешнего исполнителя, — правду они говорят только о нём. */
private val SAYS_HERE = setOf(tr("на телефоне"), tr("без сети"))

private const val PROMISE_SEPARATOR = " · "

/** Обещание называет здешнего исполнителя — стоит спросить, кого позовут на самом деле. */
fun promiseNamesHere(yields: ActionYield): Boolean =
    promiseOf(yields)?.split(PROMISE_SEPARATOR)?.any { it.trim() in SAYS_HERE } == true

/**
 * Слово о цене за тем, кто сделает работу (#1413).
 *
 * «Прочитать документ · на телефоне» в режиме «делай лучшее» уходило на компьютер и оттуда в
 * облачный сервис: способность слилась с умением компьютера, и на кнопке осталось обещание
 * телефона. Маршрут законен — режим и есть согласие, — но слова обязаны сказать, куда уйдёт
 * объект. Поэтому часть обещания, что называет здешнего исполнителя, заменяется словом
 * выбранного; остальное («текст всех страниц») — о результате и остаётся. Здешний выбран или
 * выбора нет — обещание не трогается.
 */
fun promiseOfChosen(yields: ActionYield, chosen: RealizerMeta?): ActionYield {
    if (chosen == null || chosen.kind == RealizerKind.LOCAL) return yields
    val promise = promiseOf(yields) ?: return yields
    val there = when {
        chosen.kind == RealizerKind.REMOTE && chosen.leavesCircle -> tr("через компьютер в сервис")
        chosen.kind == RealizerKind.REMOTE -> tr("на компьютере")
        else -> tr("уйдёт в сервис")
    }
    val parts = promise.split(PROMISE_SEPARATOR)
    if (parts.none { it.trim() in SAYS_HERE }) return yields
    val told = parts.map { if (it.trim() in SAYS_HERE) there else it }.distinct().joinToString(PROMISE_SEPARATOR)
    return when (yields) {
        is ActionYield.Same -> yields.copy(note = told)
        is ActionYield.New -> yields.copy(noun = told)
        else -> yields
    }
}

private fun promiseOf(yields: ActionYield): String? = when (yields) {
    is ActionYield.Same -> yields.note
    is ActionYield.New -> yields.noun
    else -> null
}
