package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
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
 */
class PhoneAskedBeforeWaitingTest {

    @get:Rule val temp = TemporaryFolder()

    private val action = PcRemoteAction("call", "Позвонить")

    /** Часы двигаются руками: «телефон отвечал минуту назад» и «молчит десять минут» — разные миры. */
    private class Hands(var at: Long) : Clock {
        override fun now(): Long = at
    }

    private fun state(box: Outbox, now: Long, lastContact: Long?): DesktopState {
        val hands = Hands(lastContact ?: now)
        val state = DesktopState(
            registry = DesktopRegistry(emptySet()),
            resolver = DesktopResolver(emptySet()),
            clipboard = { },
            outbox = box,
            clock = hands,
        )
        if (lastContact != null) state.heard()
        hands.at = now
        return state
    }

    private fun item() = InboxItem(
        PointObject("id", "text/plain", ScratchRef(temp.newFile("объект.txt").absolutePath), ObjectState(ObjectKind.TEXT)),
    )

    @Test
    fun `телефон на связи — выбирается молча, без вопросов`() {
        val box = Outbox(temp.newFolder("outbox-live"))
        val state = state(box, now = 10_000, lastContact = 9_000)

        state.sendToPhone(item(), action)
        Thread.sleep(200)

        assertNull("живой телефон спрашивать незачем", state.phoneAsk.value)
        assertEquals("просьба не ушла живому телефону", 1, box.entries().size)
    }

    @Test
    fun `телефон молчит — вопрос до дела, а не отчёт после`() {
        val box = Outbox(temp.newFolder("outbox-silent"))
        val state = state(box, now = 10 * 60_000, lastContact = 0)

        state.sendToPhone(item(), action)
        Thread.sleep(200)

        val ask = state.phoneAsk.value
        assertNotNull("молчащий телефон обязан быть выбором человека, а не нашим", ask)
        assertTrue("в вопросе не названо действие: " + ask!!.title, ask.title.contains("Позвонить"))
        assertTrue("просьба ушла молча, до ответа человека", box.entries().isEmpty())
    }

    @Test
    fun `согласился ждать — просьба уходит в почту телефона`() {
        val box = Outbox(temp.newFolder("outbox-approved"))
        val state = state(box, now = 10 * 60_000, lastContact = 0)
        state.sendToPhone(item(), action)
        Thread.sleep(200)

        state.approvePhone()
        Thread.sleep(200)

        assertNull(state.phoneAsk.value)
        assertEquals("согласие не положило просьбу", 1, box.entries().size)
        assertTrue("состояние не названо словами", state.message.value.orEmpty().contains("ждёт телефона"))
    }

    @Test
    fun `отказался — ничего не уходит, и действие остаётся доступным`() {
        val box = Outbox(temp.newFolder("outbox-declined"))
        val state = state(box, now = 10 * 60_000, lastContact = 0)
        state.sendToPhone(item(), action)
        Thread.sleep(200)

        state.declinePhone()

        assertNull(state.phoneAsk.value)
        assertTrue("отказ всё-таки отправил просьбу", box.entries().isEmpty())
        val said = state.message.value.orEmpty()
        assertTrue("отказ не сказал, что ничего не ушло: " + said, said.contains("Ничего не отправлено"))
    }

    @Test
    fun `телефон не отвечал никогда — тоже спрашиваем`() {
        val box = Outbox(temp.newFolder("outbox-never"))
        val state = state(box, now = 10_000, lastContact = null)

        state.sendToPhone(item(), action)
        Thread.sleep(200)

        assertNotNull("неизвестный телефон — не молчаливый выбор", state.phoneAsk.value)
        assertTrue(box.entries().isEmpty())
    }
}
