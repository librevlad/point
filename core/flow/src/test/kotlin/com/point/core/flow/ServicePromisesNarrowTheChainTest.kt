package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обещание каждого сервиса объявлено, и режим сужает цепочку, а не обнуляет её (#945).
 *
 * Средний режим называется «Не учатся на моём» и обещает: наружу — только к тем, кто
 * письменно обещал не учиться на присланном. Правило было написано (`allowedBy`), но не
 * подключено: у всей цепочки стояло одно общее «ничего не обещали», и режим на любое умное
 * действие отвечал «такого сейчас нет». То есть выключал AI целиком, называясь иначе.
 *
 * Решение владельца 13.08.2026: «Объявить обещание каждого сервиса».
 */
class ServicePromisesNarrowTheChainTest {

    private class Level(private val level: PrivacyLevel) : CloudPrivacySettings {
        override fun level() = level
        override suspend fun setLevel(level: PrivacyLevel) = Unit
    }

    private class Service(override val serviceId: String) : LlmClient {
        var calls = 0
        override val configured = true
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            calls++
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/scratch/ответ.txt"))
        }
    }

    private val obj = PointObject(
        id = "doc",
        mime = "text/plain",
        uri = ScratchRef("/scratch/рахунок.txt"),
        state = ObjectState(ObjectKind.TEXT),
    )

    private fun chain(vararg services: Service, level: PrivacyLevel) = FallbackLlmClient(
        providers = services.toList(),
        facts = object : AiFacts {
            override fun all(): Map<String, AiFact> = emptyMap()
            override fun remember(providerId: String, outcome: AiOutcome) = Unit
        },
        network = NetworkAvailability { true },
        privacy = Level(level),
    )

    @Test fun `строгий режим берёт того, кто обещал, и минует того, кто учится`() {
        val trains = Service(MISTRAL_PROVIDER_ID)
        val promised = Service(GROQ_PROVIDER_ID)

        runBlocking { chain(trains, promised, level = PrivacyLevel.NO_TRAINING).run(obj, "прочитай") }

        assertEquals("присланное ушло тому, кто учится на нём", 0, trains.calls)
        assertEquals(1, promised.calls)
    }

    @Test fun `открытый режим берёт первого — обещание там не спрашивается`() {
        val trains = Service(MISTRAL_PROVIDER_ID)

        runBlocking { chain(trains, level = PrivacyLevel.FREE_FIRST).run(obj, "прочитай") }

        assertEquals(1, trains.calls)
    }

    @Test fun `никто не обещал — сказано словами, а не молчанием`() {
        val trains = Service(MISTRAL_PROVIDER_ID)

        val why = runCatching {
            runBlocking { chain(trains, level = PrivacyLevel.NO_TRAINING).run(obj, "прочитай") }
        }.exceptionOrNull()?.message

        assertEquals(chainClosedBy(PrivacyLevel.NO_TRAINING), why)
        assertEquals(0, trains.calls)
    }

    @Test fun `в строгом режиме остаётся кому читать`() {
        val promised = AI_PROVIDERS.filter { allowedAt(PrivacyLevel.NO_TRAINING, it.privacy) }

        assertTrue("режим сужает список до пустого — это не сужение, а выключение", promised.size >= 3)
    }

    @Test fun `у каждого объявленного обещания названо, где оно написано и когда читалось`() {
        val silent = AI_PROVIDERS.filter { it.promiseSource.isNullOrBlank() || it.promiseCheckedAt.isNullOrBlank() }

        assertTrue("обещание без источника проверить нечем: ${silent.map { it.id }}", silent.isEmpty())
    }
}
