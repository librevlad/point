package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Бюджет ответа телефону — не таймаут работы: долгое действие не обрывается, телефону
 * сразу уходит честное «ещё работаю», а готовое остаётся объектом на самом компьютере.
 * Само в очередь к телефону оно не уезжает — «только по кнопке „На телефон“»
 * (решение владельца, #598).
 */
class SlowPcActionTest {

    @get:Rule val temp = TemporaryFolder()

    private class Says(id: String) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    }

    private class Slow(
        id: String,
        private val dir: File,
        private val delayMs: Long,
        private val fail: Boolean = false,
    ) : Realizer {
        override val capabilityId = CapabilityId(id)

        @Volatile var finished = false
        @Volatile var cancelled = false

        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            try {
                kotlinx.coroutines.delay(delayMs)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            finished = true
            if (fail) return ActionResult.Failure("сервис не ответил", recoverable = true)
            val out = File(dir, "result.txt").apply { writeText("готово") }
            return ActionResult.Success(
                ResultObject(
                    ObjectKind.TEXT, "text/plain", ScratchRef(out.absolutePath),
                    mapOf("name" to "Текст со снимка", "entity.phone" to "+380671234567"),
                ),
            )
        }
    }

    private class Pick(private val byId: Map<String, Realizer>) : Resolver {
        override fun realizerFor(capabilityId: CapabilityId): Realizer = byId.getValue(capabilityId.value)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!what() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("не дождались условия за ${timeoutMs}мс", what())
    }

    private fun harness(realizer: Slow, outbox: Outbox): Pair<DesktopState, InboxItem> {
        val state = DesktopState(
            DesktopRegistry(setOf(Says(realizer.capabilityId.value))),
            Pick(mapOf(realizer.capabilityId.value to realizer)),
            clipboard = { },
            outbox = outbox,
        )
        val source = temp.newFile("исходный.jpg").apply { writeText("байты") }
        val item = InboxItem(
            PointObject("in", "image/jpeg", ScratchRef(source.absolutePath), ObjectState(ObjectKind.IMAGE)),
        )
        return state to item
    }

    @Test
    fun `быстрое действие отвечает результатом, как раньше`() {
        val outbox = Outbox(temp.newFolder("out-fast"))
        val slow = Slow("read", temp.newFolder("r1"), delayMs = 10)
        val (state, item) = harness(slow, outbox)

        val result = state.runRemoteActionNow("read", item, 5_000)

        assertTrue(result is ActionResult.Success)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun `долгое действие не обрывается, но само в очередь к телефону не уезжает (#598)`() {
        val outbox = Outbox(temp.newFolder("out-slow"))
        val slow = Slow("read", temp.newFolder("r2"), delayMs = 400)
        val (state, item) = harness(slow, outbox)

        val result = state.runRemoteActionNow("read", item, 50)

        assertEquals(DesktopState.STILL_WORKING, (result as ActionResult.Done).message)

        waitUntil { slow.finished }
        Thread.sleep(100)
        assertFalse("работа не смеет отменяться бюджетом ответа", slow.cancelled)
        assertTrue("телефон забирает только отправленное человеком", outbox.entries().isEmpty())
    }

    @Test
    fun `долгий провал тоже ничего не кладёт в очередь`() {
        val outbox = Outbox(temp.newFolder("out-fail"))
        val slow = Slow("read", temp.newFolder("r3"), delayMs = 200, fail = true)
        val (state, item) = harness(slow, outbox)

        val result = state.runRemoteActionNow("read", item, 50)

        assertEquals(DesktopState.STILL_WORKING, (result as ActionResult.Done).message)
        waitUntil { slow.finished }
        Thread.sleep(100)
        assertTrue(outbox.entries().isEmpty())
    }
}

/**
 * «На телефон» — действие самого компьютера: рекламировать его телефону бессмысленно
 * (объект и так на телефоне; на экране телефона строка звучала «На телефон на ПК»).
 */
class PcCapsAdvertisedTest {
    @org.junit.Test
    fun `на телефон не рекламируется телефону`() {
        val advertised = com.point.core.flow.advertisedActions(setOf(PcToPhoneCapability()))
        org.junit.Assert.assertTrue(advertised.none { it.id == "pc-to-phone" })
    }
}
