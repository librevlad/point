package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ждущий шаг узнаёт, чем всё кончилось (#1336, #1344).
 *
 * «На телефон» пишет в «ПУТЬ» шаг «‹действие› · ждёт телефона». Шаг ложится на диск и
 * переживает перезапуск, а поправить его умела ровно одна дорога — правда про стук. Ни
 * забор телефоном, ни уборка очереди по сроку журнала не касались:
 *
 * - телефон забрал объект и делает работу, а «ПУТЬ» всё звал человека открыть Point и
 *   забрать (#1336);
 * - очередь забыла просьбу по сроку (#1317), а «ПУТЬ» звал за объектом, которого у Point
 *   больше нет, — и почему, не сказано нигде (PC3, #1344).
 *
 * Шов один на обе дороги, потому что вопрос у человека один: «что стало с моей просьбой».
 * Отличается ответ.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StepLearnsItsOutcomeTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    private lateinit var pc: DesktopState

    private val call = PcRemoteAction("call", "Позвонить")

    /** Очередь со швом — тем самым, что подключает `Main`: обе дороги идут через него. */
    private fun outbox(): Outbox =
        Outbox(temp.newFolder("outbox")) { id, taken -> pc.outboxEntryGone(id, taken) }

    private fun state(box: Outbox): DesktopState {
        pc = DesktopState(
            registry = DesktopRegistry(setOf(PcToPhoneCapability())),
            resolver = DesktopResolver(setOf(PcToPhoneRealizer(box, knockPhone = {}))),
            clipboard = { },
            outbox = box,
            knockPhone = { emptyMap() },
            background = dispatcher,
            io = dispatcher,
        )
        return pc
    }

    private fun item() = InboxItem(
        PointObject(
            "id",
            "text/plain",
            ScratchRef(temp.newFile("объект.txt").apply { writeText("привет") }.absolutePath),
            ObjectState(ObjectKind.TEXT),
        ),
    )

    private fun lastStep(item: InboxItem) =
        pc.journal.value.first { it.path == item.obj.uri.value }.steps.last()

    private fun queued(): InboxItem {
        val obj = item()
        pc.onReceived(obj)
        pc.sendToPhone(obj, call)
        pc.approvePhone()
        advanceUntilIdleOn()
        assertEquals("шаг не встал ждущим", StepOutcome.AWAITING, lastStep(obj).outcome)
        assertEquals(WAITS_FOR_PHONE, lastStep(obj).note)
        return obj
    }

    private fun advanceUntilIdleOn() = dispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `телефон забрал — шаг говорит об этом`() = runTest(dispatcher) {
        val box = outbox()
        state(box)
        val obj = queued()

        box.remove(box.entries().single().id)
        advanceUntilIdle()

        assertEquals("«ПУТЬ» всё зовёт забрать уже забранное", PHONE_TOOK_IT, lastStep(obj).note)
    }

    @Test
    fun `очередь забыла по сроку — шаг больше не зовёт за пустотой`() = runTest(dispatcher) {
        val box = outbox()
        state(box)
        val obj = queued()

        box.forgetOlderThan(System.currentTimeMillis() + 1)
        advanceUntilIdle()

        assertEquals("шаг остался ждущим над пустой очередью", StepOutcome.FAILED, lastStep(obj).outcome)
        assertEquals(QUEUE_FORGOT_IT, lastStep(obj).note)
        assertNotEquals("человека всё ещё зовут за объектом", WAITS_FOR_PHONE, lastStep(obj).note)
    }

    /** Чужая запись очереди чужой шаг не трогает: у каждого свой номер. */
    @Test
    fun `ушедшая чужая запись ждущего шага не касается`() = runTest(dispatcher) {
        val box = outbox()
        state(box)
        val obj = queued()

        pc.outboxEntryGone(id = 9999, taken = true)
        advanceUntilIdle()

        assertEquals(WAITS_FOR_PHONE, lastStep(obj).note)
    }
}
