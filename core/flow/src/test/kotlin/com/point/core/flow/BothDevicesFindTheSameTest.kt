package com.point.core.flow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одно и то же в одном и том же тексте (#934).
 *
 * Счёт `Счёт 4417 на 12 500 грн, оплатить до 30.09.2026, телефон 067 636 05 60`: компьютер
 * находил в нём телефон, телефон — нет. Не потому, что не умеет: правила, которыми ищет
 * компьютер, лежали в общем ядре и на телефоне не звались вовсе.
 */
class BothDevicesFindTheSameTest {

    private val invoice = "Счёт 4417 на 12 500 грн, оплатить до 30.09.2026, телефон 067 636 05 60"

    /** Движок сущностей телефона: язык понимает, местный номер за телефон не считает. */
    private val engine = object : EntityExtractor {
        override suspend fun extract(text: String) =
            listOf(Entity(EntityType.DATE_TIME, "30.09.2026", line = text))
    }

    @Test fun `телефон находит то же, что и компьютер`() {
        val both = BothEntityExtractors(listOf(engine, RegexEntityExtractor()))

        val found = runBlocking { both.extract(invoice) }

        assertTrue("номера нет", found.any { it.type == EntityType.PHONE })
        assertTrue("даты нет", found.any { it.type == EntityType.DATE_TIME })
    }

    @Test fun `одно и то же значение дважды не приходит`() {
        val twice = BothEntityExtractors(listOf(RegexEntityExtractor(), RegexEntityExtractor()))

        val found = runBlocking { twice.extract(invoice) }

        assertEquals(found.distinctBy { it.type to it.value }.size, found.size)
    }

    @Test fun `упавший способ не уносит с собой второй`() {
        val broken = object : EntityExtractor {
            override suspend fun extract(text: String): List<Entity> = error("движок не поднялся")
        }
        val both = BothEntityExtractors(listOf(broken, RegexEntityExtractor()))

        val found = runBlocking { both.extract(invoice) }

        assertTrue(found.any { it.type == EntityType.PHONE })
    }
}
