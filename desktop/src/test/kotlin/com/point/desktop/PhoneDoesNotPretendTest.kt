package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Компьютер не обещает за телефон того, чего телефон не сделает (#785).
 *
 * Прежде на экране компьютера стояло: «Просьба подождёт в его почте и выполнится, когда вы
 * откроете Point на телефоне». Не выполнялась никогда — телефон читает свою почту только ради
 * ответа на собственный запрос, а всё остальное выбрасывает `Mailbox.drain` при первом же
 * обращении к серверу.
 *
 * Половина контракта связки хуже его отсутствия ровно этим: тестировщик сообщил бы не о
 * недоделке, а о том, что Point врёт. Связка остаётся односторонней — и человек это видит.
 */
class PhoneDoesNotPretendTest {

    @get:Rule val temp = TemporaryFolder()

    private fun state(box: Outbox? = null) = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
        outbox = box,
    )

    private fun item() = InboxItem(
        PointObject("id", "text/plain", ScratchRef("/tmp/объект"), ObjectState(ObjectKind.TEXT)),
    )

    /** Приёмка 2: причина видна до нажатия, а не после напрасного ожидания. */
    @Test
    fun `работа телефона названа недоступной прямо в списке`() {
        val pc = state()
        pc.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить")))

        val phoneAction = pc.actionsFor(item()).single { it.onPhone }

        assertEquals(PHONE_DOES_NOT_RUN_REQUESTS, phoneAction.unavailable)
    }

    /** Объявление телефона «у меня всё хорошо» границы связки не отменяет. */
    @Test
    fun `даже объявленное телефоном как доступное остаётся недоступным`() {
        val pc = state()
        pc.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить", unavailable = null)))

        assertTrue(pc.actionsFor(item()).filter { it.onPhone }.all { it.unavailable != null })
    }

    /** Приёмка 1 и 3: просьба не уезжает, и человеку сказано почему. */
    @Test
    fun `просьба не уходит в почту, и причина названа`() {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)

        pc.sendToPhone(item(), PcRemoteAction("call", "Позвонить"))
        Thread.sleep(200)

        assertEquals(0, box.entries().size)
        assertEquals(PHONE_DOES_NOT_RUN_REQUESTS, pc.message.value)
        assertNull("человека спросили про работу, которой не будет", pc.phoneAsk.value)
    }

    /** Слово о границе — про человека и его телефон, а не про почту, drain и очереди. */
    @Test
    fun `причина сказана словами человека`() {
        val said = PHONE_DOES_NOT_RUN_REQUESTS

        listOf("почт", "очеред", "drain", "mailbox", "запрос").forEach { jargon ->
            assertTrue("в причине жаргон «$jargon»: $said", jargon !in said.lowercase())
        }
        assertTrue("причина не называет виновника — телефон", "телефон" in said.lowercase())
    }

    /** Своё, здешнее, границей связки не задето: компьютер продолжает делать своё. */
    @Test
    fun `свои действия компьютера остаются доступными`() {
        val pc = state()
        pc.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить")))

        assertTrue(pc.actionsFor(item()).none { !it.onPhone && it.unavailable != null })
    }
}
