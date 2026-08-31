package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Срез 5 контракта связки (#611): исполнитель, который ответит через час, не может быть выбран
 * за спиной. Телефон отвечает сейчас — берётся молча; телефон молчит — вопрос человеку до дела,
 * а не отчёт после.
 *
 * Правило проверяется на включённой механике: сегодня телефон просьбы компьютера не исполняет
 * вовсе (#785), и это отдельная проверка в конце. Правило же переживёт тот день, когда
 * научится, — иначе его пришлось бы восстанавливать по памяти.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAskedBeforeWaitingTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * Просьба ложится в очередь в работе окна, и планировщик теста доводит эту работу до
     * конца. Здесь это важнее всего для отрицательных проверок: прежде «просьба не ушла»
     * доказывалось сном в 200 мс — то есть «не успела уйти» читалось как «не ушла», и
     * тест зеленел бы и над сломанным правилом.
     */
    private val dispatcher = StandardTestDispatcher()

    private val action = PcRemoteAction("call", "Позвонить")

    /** Часы двигаются руками: «телефон отвечал минуту назад» и «молчит десять минут» — разные миры. */
    private class Hands(var at: Long) : Clock {
        override fun now(): Long = at
    }

    private fun state(
        box: Outbox,
        now: Long,
        lastContact: Long?,
        runsRequests: Boolean = true,
    ): DesktopState {
        val hands = Hands(lastContact ?: now)
        val state = DesktopState(
            registry = DesktopRegistry(emptySet()),
            resolver = DesktopResolver(emptySet()),
            clipboard = { },
            outbox = box,
            clock = hands,
            phoneRunsRequests = runsRequests,
            background = dispatcher,
            io = dispatcher,
        )
        if (lastContact != null) state.heard("устройство-телефон")
        hands.at = now
        return state
    }

    private fun item() = InboxItem(
        PointObject("id", "text/plain", ScratchRef(temp.newFile("объект.txt").absolutePath), ObjectState(ObjectKind.TEXT)),
    )

    @Test
    fun `телефон на связи — выбирается молча, без вопросов`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox-live"))
        val state = state(box, now = 10_000, lastContact = 9_000)

        state.sendToPhone(item(), action)
        advanceUntilIdle()

        assertNull("живой телефон спрашивать незачем", state.phoneAsk.value)
        assertEquals("просьба не ушла живому телефону", 1, box.entries().size)
    }

    @Test
    fun `телефон молчит — вопрос до дела, а не отчёт после`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox-silent"))
        val state = state(box, now = 10 * 60_000, lastContact = 0)

        state.sendToPhone(item(), action)
        advanceUntilIdle()

        val ask = state.phoneAsk.value
        assertNotNull("молчащий телефон обязан быть выбором человека, а не нашим", ask)
        assertTrue("в вопросе не названо действие: " + ask!!.title, ask.title.contains("Позвонить"))
        assertTrue("просьба ушла молча, до ответа человека", box.entries().isEmpty())
    }

    @Test
    fun `согласился ждать — просьба уходит в почту телефона`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox-approved"))
        val state = state(box, now = 10 * 60_000, lastContact = 0)
        state.sendToPhone(item(), action)
        advanceUntilIdle()

        state.approvePhone()

        // Слово «ждёт телефона» проверяется сразу после согласия: дальше по времени
        // компьютер досматривает, проснулся ли телефон, и через отведённый срок молчания
        // говорит уже другое — что он не проснулся (#1108).
        runCurrent()

        assertNull(state.phoneAsk.value)
        assertEquals("согласие не положило просьбу", 1, box.entries().size)
        assertTrue("состояние не названо словами", state.message.value.orEmpty().contains("ждёт телефона"))

        advanceUntilIdle()
    }

    @Test
    fun `отказался — ничего не уходит, и действие остаётся доступным`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox-declined"))
        val state = state(box, now = 10 * 60_000, lastContact = 0)
        state.sendToPhone(item(), action)
        advanceUntilIdle()

        state.declinePhone()

        assertNull(state.phoneAsk.value)
        assertTrue("отказ всё-таки отправил просьбу", box.entries().isEmpty())
        val said = state.message.value.orEmpty()
        assertTrue("отказ не сказал, что ничего не ушло: " + said, said.contains("Ничего не отправлено"))
    }

    @Test
    fun `телефон не отвечал никогда — тоже спрашиваем`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox-never"))
        val state = state(box, now = 10_000, lastContact = null)

        state.sendToPhone(item(), action)
        advanceUntilIdle()

        assertNotNull("неизвестный телефон — не молчаливый выбор", state.phoneAsk.value)
        assertTrue(box.entries().isEmpty())
    }

    /**
     * Сегодняшняя правда (#785): телефон свою почту разбирает только ради ответа на
     * собственный запрос, а всё прочее выбрасывает `Mailbox.drain`. Значит просьба не
     * подождёт — её сотрут. Ни очереди, ни вопроса: обещание хуже отсутствия действия.
     */
    @Test
    fun `пока телефон не исполняет просьбы — ни очереди, ни вопроса`() = runTest(dispatcher) {
        val box = Outbox(temp.newFolder("outbox-off"))
        val state = state(box, now = 10_000, lastContact = 9_000, runsRequests = false)

        state.sendToPhone(item(), action)
        advanceUntilIdle()

        assertNull("человека спросили про работу, которой не будет", state.phoneAsk.value)
        assertEquals("просьба ушла в почту, где её сотрут", 0, box.entries().size)
        assertEquals(PHONE_DOES_NOT_RUN_REQUESTS, state.message.value)
    }
}
