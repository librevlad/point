package com.point.core.flow

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Канал стадий действия (#288): реализатор рассказывает, что он делает **сейчас**.
 *
 * До этого экран ожидания показывал чек-лист из трёх строк, который двигало настенное время:
 * через двенадцать секунд он упирался в последнюю строку и застывал, а «В Excel» работает
 * минуту с лишним. Консилиум назвал это прямо: «замещение реального статуса имитацией».
 * Имитация хуже пустоты — застрявшая бутафория читается как «зависло» и подрывает доверие
 * ко всему остальному, что экран говорит честно.
 *
 * Канал сделан элементом контекста корутины, а не параметром [Realizer.perform]: у контракта
 * действия десятки реализаций, и добавление параметра ради необязательной способности
 * заставило бы переписать их все. Кто умеет рассказывать — рассказывает; кто молчит — тому
 * экран показывает то, что знает точно: идущее время и кнопку отмены.
 */
class ActionProgress(private val onStage: (String) -> Unit) :
    AbstractCoroutineContextElement(Key) {

    fun report(stage: String) = onStage(stage)

    companion object Key : CoroutineContext.Key<ActionProgress>
}

/**
 * Сказать, что происходит сейчас. Вне действия (в тестах, в обогащении) — тихо ничего:
 * стадия без слушателя не ошибка, а просто некому.
 */
suspend fun reportStage(stage: String) {
    coroutineContext[ActionProgress]?.report(stage)
}
