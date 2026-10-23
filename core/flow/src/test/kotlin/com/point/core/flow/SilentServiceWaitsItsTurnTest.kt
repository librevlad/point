package com.point.core.flow

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Молчащий сервис — в конец очереди (#433).
 *
 * Сервис, который только что не ответил, ставит человека ждать его таймаут снова и снова.
 * Он идёт последним, пока не выйдет срок, но из очереди не выпадает.
 */
class SilentServiceWaitsItsTurnTest {

    private val obj = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    private fun fact(outcome: AiOutcome, at: Long) = AiFact(outcome, at)

    @Test fun `промолчавший — минуты, лимит и чужой ключ — час, ответивший — никогда`() {
        val t = 1_000_000_000L
        assertTrue(benched(fact(AiOutcome.SILENT, t), t + SILENT_BENCH_MS - 1))
        assertFalse(benched(fact(AiOutcome.SILENT, t), t + SILENT_BENCH_MS))
        assertTrue(benched(fact(AiOutcome.LIMIT, t), t + SILENT_BENCH_MS))
        assertTrue(benched(fact(AiOutcome.BAD_KEY, t), t + SPENT_BENCH_MS - 1))
        assertFalse(benched(fact(AiOutcome.LIMIT, t), t + SPENT_BENCH_MS))
        assertFalse(benched(fact(AiOutcome.ANSWERED, t), t))
        assertFalse("неизвестный сервис не наказан", benched(null, t))
    }

    @Test fun `очередь та же, только молчавшие — в конце`() {
        val now = 10 * SPENT_BENCH_MS
        val facts = mapOf("b" to fact(AiOutcome.SILENT, now - 1), "d" to fact(AiOutcome.ANSWERED, now - 1))

        assertEquals(listOf("a", "c", "d", "b"), awakeFirst(listOf("a", "b", "c", "d"), now) { facts[it] })
    }

    private class Service(override val serviceId: String, private val calls: MutableList<String>, var silent: Boolean) : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            calls += serviceId
            if (silent) error("timeout")
            return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/out/$serviceId"))
        }
    }

    private class Remembering(private val clock: () -> Long) : AiFacts {
        val known = mutableMapOf<String, AiFact>()
        override fun all() = known.toMap()
        override fun remember(providerId: String, outcome: AiOutcome) {
            known[providerId] = AiFact(outcome, clock())
        }
    }

    @Test fun `второй раз человек не ждёт того, кто только что промолчал`() = runTest {
        var t = 1_000_000_000L
        val calls = mutableListOf<String>()
        val first = Service("first", calls, silent = true)
        val second = Service("second", calls, silent = false)
        val chain = FallbackLlmClient(listOf(first, second), Remembering { t }, NetworkAvailability { true }, now = { t })

        chain.run(obj, "hi")
        assertEquals(listOf("first", "second"), calls)

        calls.clear()
        t += 60_000
        assertEquals("/out/second", chain.run(obj, "hi").uri.value)
        assertEquals("молчавший снова стоял первым", listOf("second"), calls)

        calls.clear()
        t += SILENT_BENCH_MS
        first.silent = false
        chain.run(obj, "hi")
        assertEquals("срок вышел — порядок прежний", listOf("first"), calls)
    }

    @Test fun `больше некому — пробуется и молчавший`() = runTest {
        val t = 1_000_000_000L
        val calls = mutableListOf<String>()
        val only = Service("only", calls, silent = false)
        val facts = Remembering { t }.apply { known["only"] = AiFact(AiOutcome.SILENT, t - 1) }

        val chain = FallbackLlmClient(listOf(only), facts, NetworkAvailability { true }, now = { t })

        assertEquals("/out/only", chain.run(obj, "hi").uri.value)
    }
}
