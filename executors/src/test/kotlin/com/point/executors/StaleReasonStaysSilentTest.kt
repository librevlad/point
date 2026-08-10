package com.point.executors

import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Телефон не выдаёт устаревшее состояние компьютера за нынешнее (#633).
 *
 * Живой прогон 06.08.2026: «Дать ссылку на ПК · компьютер не вошёл в аккаунт» — при том что
 * компьютер в ту же минуту был в аккаунте. Причина ехала со старым объявлением.
 */
class StaleReasonStaysSilentTest {

    private val linked = object : PcLinks {
        override fun current() = LinkedPc("pc", "Ноутбук", "key")
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private val state = ObjectState(ObjectKind.TEXT)

    private val refused = PcRemoteAction("pc-drop", "Дать ссылку", unavailable = "компьютер не вошёл в аккаунт")

    @Test fun `свежая причина показывается — она про сейчас`() {
        val cap = RemotePcCapability(refused, linked) { true }

        assertEquals("компьютер не вошёл в аккаунт", cap.missing(state))
        assertFalse("действие с причиной не берётся", cap.accepts(state))
    }

    @Test fun `устаревшая причина молчит, а не пугает выдумкой`() {
        val cap = RemotePcCapability(refused, linked) { false }

        assertNull("старое состояние выдано за нынешнее", cap.missing(state))
    }

    @Test fun `устаревшее объявление не держит действие закрытым`() {
        val cap = RemotePcCapability(refused, linked) { false }

        assertTrue("действие осталось закрытым по старой причине", cap.accepts(state))
    }
}
