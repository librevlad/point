package com.point.core.flow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Забыть всё» забывает всё (#1026).
 *
 * Стирался перечень истории, а на устройстве оставались копия объекта, слой прочитанных с
 * него слов, граф последнего объекта и переписка с моделью — и экран говорил «Пока ничего
 * не сохранено».
 */
class ForgetEverythingTest {

    @Test
    fun `забываются все места, а не первое`() = runTest {
        val forgotten = mutableListOf<String>()
        val places = listOf("история", "объект", "граф", "переписка")
            .map { name -> Memory { forgotten += name } }

        val failed = forgetEverything(places)

        assertEquals(0, failed)
        assertEquals(4, forgotten.size)
    }

    /** Недоступная папка — не повод оставить переписку с моделью на диске. */
    @Test
    fun `сбой одного места не отменяет остальных`() = runTest {
        val forgotten = mutableListOf<String>()
        val places = listOf(
            Memory { error("папка недоступна") },
            Memory { forgotten += "объект" },
            Memory { forgotten += "переписка" },
        )

        val failed = forgetEverything(places)

        assertEquals(1, failed)
        assertTrue("остальные места не забыты", forgotten.size == 2)
    }
}
