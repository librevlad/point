package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #689 (охота 2026-08-09): человек выбрал «Только на телефоне» — «Ничего не уходит
 * с телефона», — нажал «Понять», и объект уехал в облако. Проверка режима стояла
 * внутри одной ветки распознавания, а «Понять», «Перевести», «AI», «В Excel»,
 * «В Word» и расшифровка речи её не спрашивали вовсе.
 */
class PrivacyGuardedLlmClientTest {

    private class Spy : LlmClient {
        var calls = 0
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            calls++
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/tmp/answer.txt"))
        }
    }

    private class Level(private val level: PrivacyLevel) : CloudPrivacySettings {
        override fun level() = level
        override suspend fun setLevel(level: PrivacyLevel) = Unit
    }

    private val obj = PointObject("1", "text/plain", ScratchRef("/tmp/o.txt"), ObjectState(ObjectKind.TEXT))

    @Test
    fun `в режиме «только на телефоне» наружу не уходит ничего`() = runBlocking {
        val spy = Spy()
        val guarded = PrivacyGuardedLlmClient(spy, Level(PrivacyLevel.DEVICE_ONLY))

        val failure = runCatching { guarded.run(obj, "прочитай") }.exceptionOrNull()

        assertEquals(0, spy.calls)
        assertTrue(
            "причина названа словами человека: ${failure?.message}",
            failure?.message?.contains("настройк") == true,
        )
    }

    @Test
    fun `режим «максимум бесплатного» пропускает наружу`() = runBlocking {
        val spy = Spy()
        val guarded = PrivacyGuardedLlmClient(spy, Level(PrivacyLevel.FREE_FIRST))

        guarded.run(obj, "прочитай")

        assertEquals(1, spy.calls)
    }

    /**
     * #945: у каждого сервиса своё обещание, и средний режим сужает цепочку, а не обнуляет
     * её. Общий шов пропускает — кого именно из сервисов брать, решает сама цепочка.
     */
    @Test
    fun `«не учатся на моём» пропускает — сервисы разбирает цепочка`() = runBlocking {
        val spy = Spy()
        val guarded = PrivacyGuardedLlmClient(spy, Level(PrivacyLevel.NO_TRAINING))

        guarded.run(obj, "прочитай")

        assertEquals(1, spy.calls)
    }

    @Test
    fun `цепочке, которая ничего не обещала, средний режим по-прежнему закрыт`() = runBlocking {
        val spy = Spy()
        val guarded = PrivacyGuardedLlmClient(spy, Level(PrivacyLevel.NO_TRAINING), AI_CHAIN_PRIVACY)

        runCatching { guarded.run(obj, "прочитай") }

        assertEquals("сервис про обучение ничего не обещал", 0, spy.calls)
    }

    @Test
    fun `закрытый режим не прячет действие — ключ остаётся на месте`() {
        val guarded = PrivacyGuardedLlmClient(
            object : LlmClient {
                override suspend fun run(obj: PointObject, prompt: String) = error("не должно вызываться")
                override val configured = true
            },
            Level(PrivacyLevel.DEVICE_ONLY),
        )

        assertTrue("дверь видна и объясняет отказ по тапу", guarded.configured)
    }

    @Test
    fun `без ключа действие остаётся ненастроенным и в открытом режиме`() {
        val guarded = PrivacyGuardedLlmClient(
            object : LlmClient {
                override suspend fun run(obj: PointObject, prompt: String) = error("не должно вызываться")
                override val configured = false
            },
            Level(PrivacyLevel.FREE_FIRST),
        )

        assertFalse(guarded.configured)
    }
}
