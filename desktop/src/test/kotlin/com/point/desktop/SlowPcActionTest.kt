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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Бюджет ответа телефону — не таймаут работы (Product Constitution PC4): долгое
 * действие не обрывается, телефону сразу уходит честное «ещё работаю», а готовый
 * результат уезжает существующей очередью ПК→телефон вместе со знанием.
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

        /** Шаг состоялся словами, без объекта — как отмена у диалога «Сохранить в…» (#1073). */
        private val saysDone: ActionResult.Done? = null,
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
            saysDone?.let { return it }
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
    fun `долгое действие не обрывается — телефону честный ответ, результат в очереди со знанием`() {
        val outbox = Outbox(temp.newFolder("out-slow"))
        val slow = Slow("read", temp.newFolder("r2"), delayMs = 400)
        val (state, item) = harness(slow, outbox)

        val result = state.runRemoteActionNow("read", item, 50)

        assertEquals(DesktopState.STILL_WORKING, (result as ActionResult.Done).message)

        waitUntil { slow.finished && outbox.entries().isNotEmpty() }
        assertFalse("работа не смеет отменяться бюджетом ответа", slow.cancelled)
        val entry = outbox.entries().single()
        assertEquals("Текст со снимка", entry.meta["name"])
        assertEquals("знание едет вместе с результатом", "+380671234567", entry.meta["entity.phone"])
    }

    /**
     * Любой исход просьбы соседу возвращается телефону (#1073, решение владельца) — и отказ
     * тоже: прежде долгий провал не клал в очередь ничего, и телефон вечно ждал обещанного.
     */
    @Test
    fun `долгий отказ едет телефону исходом без объекта, а не молчанием`() {
        val outbox = Outbox(temp.newFolder("out-fail"))
        val slow = Slow("read", temp.newFolder("r3"), delayMs = 200, fail = true)
        val (state, item) = harness(slow, outbox)

        val result = state.runRemoteActionNow("read", item, 50)

        assertEquals(DesktopState.STILL_WORKING, (result as ActionResult.Done).message)
        waitUntil { slow.finished && outbox.entries().isNotEmpty() }
        val entry = outbox.entries().single()
        val f = com.point.core.flow.PcResultFields
        assertTrue("исход без объекта — слова домой, не вещь", f.outcomeOnly(entry.meta))
        assertEquals(com.point.core.flow.PcActionOutcome.Failed("сервис не ответил"), f.outcomeOf(entry.meta))
        assertNull("файла у исхода нет", outbox.file(entry.id))
    }

    /**
     * Отмена у системного диалога на компьютере — `Done("Отменено")` без объекта (#1073): до
     * телефона она не доезжала никак, и «ещё работает» висело там навсегда. Теперь исход
     * едет той же очередью, к своему объекту, со знанием, если оно было.
     */
    @Test
    fun `долгое «готово» без объекта — отмена у диалога — едет телефону к своему объекту`() {
        val outbox = Outbox(temp.newFolder("out-cancel"))
        val cancelled = "Отменено"
        val slow = Slow(
            "pc-save-as", temp.newFolder("r5"), delayMs = 200,
            saysDone = ActionResult.Done(
                cancelled,
                com.point.core.model.Findings(metadata = mapOf("entity.phone" to "+380671234567")),
            ),
        )
        val (state, item) = harness(slow, outbox)
        val fromPhone = item.copy(obj = item.obj.copy(metadata = mapOf(com.point.core.flow.META_ORIGIN_ID to "phone-obj")))

        val result = state.runRemoteActionNow("pc-save-as", fromPhone, 50)

        assertEquals(DesktopState.STILL_WORKING, (result as ActionResult.Done).message)
        waitUntil { slow.finished && outbox.entries().isNotEmpty() }
        val entry = outbox.entries().single()
        val f = com.point.core.flow.PcResultFields
        assertEquals(com.point.core.flow.PcActionOutcome.Done(cancelled), f.outcomeOf(entry.meta))
        assertEquals("исход едет к объекту телефона, а не к копии на компьютере", "phone-obj", entry.meta[com.point.core.flow.PcExecFields.HOME])

        // Просьба названа так, как её видел человек на телефоне: он нажимал «Сохранить на
        // компьютере», а не то, как это же умение зовётся здесь.
        assertEquals(
            "исход называет просьбу не тем именем, под которым она объявлена телефону",
            phoneFacingLabel("pc-save-as", "pc-save-as"),
            entry.meta[com.point.core.flow.PcExecFields.LABEL],
        )
        assertEquals("понятое едет вместе с исходом", "+380671234567", entry.meta[f.UNDERSTOOD + "entity.phone"])
        assertNull(outbox.file(entry.id))
    }

    @Test
    fun `очередь не приняла результат — компьютер говорит об этом, а не молчит`() {
        val broken = Outbox(temp.newFile("не-папка"))
        val slow = Slow("read", temp.newFolder("r4"), delayMs = 200)
        val (state, item) = harness(slow, broken)

        state.runRemoteActionNow("read", item, 50)

        waitUntil { slow.finished }
        waitUntil {
            state.message.value == "Результат не лёг в очередь для телефона — проверьте, что на диске есть место"
        }
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
