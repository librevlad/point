package com.point.core.flow

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionProgressTest {

    @Test
    fun `реализатор рассказывает, что делает сейчас`() = runTest {
        val heard = mutableListOf<String>()

        withContext(ActionProgress { heard += it }) {
            reportStage("Читаю страницу")
            reportStage("Таблицу читают 2 модели одновременно")
        }

        assertEquals(listOf("Читаю страницу", "Таблицу читают 2 модели одновременно"), heard)
    }

    @Test
    fun `стадия без слушателя — не ошибка, а просто некому`() = runTest {
        reportStage("никто не слушает")
        assertTrue("тишина вместо падения", true)
    }
}
