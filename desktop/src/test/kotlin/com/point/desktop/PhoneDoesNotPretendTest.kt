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
 * Компьютер обещает за телефон ровно то, что телефон сделает (#785, включено в #817).
 *
 * Раньше здесь стояла обратная проверка: связка односторонняя, и компьютер честно отказывал.
 * Причина отказа оказалась неверной — она говорила, что просьба поедет почтой и будет стёрта
 * чисткой ящика. На деле просьба почтой не едет: она ложится в папку на диске самого
 * компьютера, а телефон сам приходит за ней и давно умеет выполнять названное действие.
 *
 * Половина контракта связки хуже его отсутствия — поэтому обещание должно быть точным:
 * просьба ждёт, пока человек откроет Point на телефоне и заберёт объект.
 */
class PhoneDoesNotPretendTest {

    @get:Rule val temp = TemporaryFolder()

    private fun state(box: Outbox? = null) = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
        outbox = box,
    )

    /** Объект настоящий: очередь копирует файл, и мнимый путь в неё не ляжет. */
    private fun item() = InboxItem(
        PointObject(
            "id",
            "text/plain",
            ScratchRef(temp.newFile("объект.txt").apply { writeText("+380671234567") }.absolutePath),
            ObjectState(ObjectKind.TEXT),
        ),
    )

    @Test
    fun `работа телефона доступна и не носит чужой причины`() {
        val pc = state()
        pc.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить")))

        val phoneAction = pc.actionsFor(item()).single { it.onPhone }

        assertNull("действие закрыто причиной, которой больше нет", phoneAction.unavailable)
    }

    /** Слово самого телефона сильнее: сказал «не могу» — значит не может. */
    @Test
    fun `отказ телефона остаётся отказом`() {
        val said = "нет сети"
        val pc = state()
        pc.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить", unavailable = said)))

        assertEquals(said, pc.actionsFor(item()).single { it.onPhone }.unavailable)
    }

    @Test
    fun `телефон не на связи — сначала спрашивают, потом кладут в очередь`() {
        val box = Outbox(temp.newFolder("outbox"))
        val pc = state(box)

        pc.sendToPhone(item(), PcRemoteAction("call", "Позвонить"))

        // Человека спрашивают, а не обещают за телефон: он сам решает, ждать ли.
        val ask = pc.phoneAsk.value
        assertTrue("не спросили про ожидание", ask != null)
        assertTrue("не сказано, чего ждать: ${ask?.what}", ask?.what.orEmpty().contains("откроете Point"))
        assertEquals("положили, не спросив", 0, box.entries().size)

        pc.approvePhone()
        Thread.sleep(400)

        assertEquals("согласие не положило просьбу в очередь", 1, box.entries().size)
        assertEquals("просьба уехала без названия работы", "call", box.entries().single().meta["pc.action"])
    }

    /** Слово о связке — про человека и его телефон, а не про почту, drain и очереди. */
    @Test
    fun `сказано словами человека`() {
        val said = "Позвонить — ждёт телефона: откройте Point на телефоне и заберите объект"

        listOf("почт", "drain", "mailbox", "запрос", "очеред").forEach { jargon ->
            assertTrue("в словах жаргон «$jargon»: $said", jargon !in said.lowercase())
        }
        assertTrue("не названо, что нужно от человека", "телефон" in said.lowercase())
    }

    /** Своё, здешнее, связкой не задето: компьютер продолжает делать своё. */
    @Test
    fun `свои действия компьютера остаются доступными`() {
        val pc = state()
        pc.setPhoneCaps(listOf(PcRemoteAction("call", "Позвонить")))

        assertTrue(pc.actionsFor(item()).none { !it.onPhone && it.unavailable != null })
    }
}
