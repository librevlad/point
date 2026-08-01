package com.point.executors

import com.point.core.flow.ActionProgress
import kotlinx.coroutines.withContext

/**
 * Что услышал экран, пока действие работало (#288).
 *
 * Канал стадий — элемент контекста корутины, а не параметр [com.point.core.flow.Realizer.perform],
 * поэтому проверить его можно только запустив реализатор ВНУТРИ такого контекста. Помощник держит
 * эту обвязку в одном месте: дюжина тестов, каждый со своим `withContext(ActionProgress { … })`,
 * разъехалась бы по мелочам, а проверка тут ровно одна — доходят ли слова действия наружу.
 *
 * Пустой список — тоже ответ: у действия, которое не работало (готовый сайдкар, делегирование
 * чужому реализатору), стадий быть не должно.
 */
internal suspend fun stagesHeard(action: suspend () -> Unit): List<String> {
    val heard = mutableListOf<String>()
    withContext(ActionProgress { heard += it }) { action() }
    return heard
}
