package com.point.core.flow

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стадии действия — его собственные слова (#288). Консилиум назвал прежний экран ожидания
 * «замещением реального статуса имитацией»: чек-лист двигало время, а не работа.
 */
class ActionProgressTest {

    @Test
    fun `реализатор рассказывает, что делает сейчас`() = runTest {
        val heard = mutableListOf<String>()

        withContext(ActionProgress { heard += it }) {
            reportStage("Читаю страницу")
            reportStage("Модель 1 из 2 читает таблицу")
        }

        assertEquals(listOf("Читаю страницу", "Модель 1 из 2 читает таблицу"), heard)
    }

    @Test
    fun `стадия без слушателя — не ошибка, а просто некому`() = runTest {
        reportStage("никто не слушает")
        assertTrue("тишина вместо падения", true)
    }
}
