package com.point.executors

import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.PcUnreachable
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcActionTest {

    private class FakeLinks(var pc: LinkedPc? = null) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) { this.pc = pc }
        override suspend fun clear() { pc = null }
    }

    private class FakeTransport(var outcome: PcSendOutcome = PcSendOutcome.Sent()) : PcTransport {
        var sentMeta: Map<String, String>? = null
        var sentName: String? = null
        override suspend fun send(
            pc: LinkedPc, obj: PointObject, fileName: String, meta: Map<String, String>, action: String?,
        ): PcSendOutcome {
            sentName = fileName
            sentMeta = meta
            return outcome
        }
        override suspend fun fetchCaps(pc: LinkedPc): List<com.point.core.flow.PcRemoteAction>? = null
        override suspend fun fetchOutbox(pc: LinkedPc): List<com.point.core.flow.PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String): Boolean = false
        override suspend fun ackOutbox(pc: LinkedPc, id: Int) {}
        override suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<com.point.core.flow.PcRemoteAction>): Boolean = true
        override suspend fun exchangeSecrets(
            pc: LinkedPc,
            mine: com.point.core.flow.SharedSecrets,
        ): com.point.core.flow.SharedSecrets? = null
    }

    private val linked = LinkedPc("d-pc", "Домашний ПК", "ключ")

    private fun obj(meta: Map<String, String> = emptyMap()) = PointObject(
        id = "o", mime = "image/jpeg", uri = ScratchRef("/tmp/x.jpg"),
        state = ObjectState(ObjectKind.IMAGE), metadata = meta,
    )

    private fun reasonOf(result: ActionResult) = (result as ActionResult.Failure).reason

    @Test
    fun `компьютера в круге нет — пузырька нет, но подсказка учит, чем это чинится`() {
        val cap = PcCapability(FakeLinks(null))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertEquals("войдите в аккаунт на компьютере", cap.missing(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `компьютер в круге — пузырёк виден, и он терминальный`() {
        val cap = PcCapability(FakeLinks(linked))
        val state = ObjectState(ObjectKind.IMAGE)
        assertTrue(cap.accepts(state))
        assertTrue(cap.produces(state) === state)
    }

    @Test
    fun `понимание об объекте едет вместе с ним`() = runTest {
        val transport = FakeTransport()
        val realizer = PcRealizer(FakeLinks(linked), transport)

        val result = realizer.perform(obj(mapOf("name" to "чек.jpg", "entity.phone" to "+3806")), null)

        assertTrue(result is ActionResult.Done)
        assertEquals("чек.jpg", transport.sentName)
        assertEquals("+3806", transport.sentMeta!!["entity.phone"])
    }

    @Test
    fun `отправленное называет место — окно Point и папку Point`() = runTest {

        // #646, решение владельца: «Отправлено на компьютер — появится в окне Point
        // и в папке Point». Боль «куда исчез файл» (PC3): место названо словами.
        val result = PcRealizer(FakeLinks(linked), FakeTransport()).perform(obj(), null)

        val said = (result as ActionResult.Done).message
        assertTrue("не названо окно: «$said»", said.contains("окне Point"))
        assertTrue("не названа папка: «$said»", said.contains("папке Point"))
    }

    @Test
    fun `отправка на компьютер называет себя, пока идёт`() = runTest {
        val heard = stagesHeard {
            PcRealizer(FakeLinks(linked), FakeTransport()).perform(obj(), null)
        }

        assertEquals(listOf("Отправляю на компьютер"), heard)
    }

    @Test
    fun `без компьютера в круге ждать нечего — и слов о работе нет`() = runTest {

        val heard = stagesHeard { PcRealizer(FakeLinks(null), FakeTransport()).perform(obj(), null) }

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `компьютера нет в круге — сказано, что войти надо на нём`() = runTest {
        val transport = FakeTransport(PcSendOutcome.Unreachable("404", PcUnreachable.NOT_IN_CIRCLE))

        val reason = reasonOf(PcRealizer(FakeLinks(linked), transport).perform(obj(), null))

        assertEquals(
            "Компьютера нет в вашем круге. Запустите «Point для ПК» и войдите в тот же аккаунт.",
            reason,
        )
    }

    @Test
    fun `компьютер не запущен — сказано именно это, а не «недоступен»`() = runTest {

        val transport = FakeTransport(PcSendOutcome.Unreachable("нет ответа", PcUnreachable.PC_ASLEEP))

        val reason = reasonOf(PcRealizer(FakeLinks(linked), transport).perform(obj(), null))

        assertEquals("Компьютер не отвечает. Проверьте, что «Point для ПК» на нём запущен.", reason)
    }

    @Test
    fun `сервер молчит — виноват не компьютер, и про него ничего не утверждается`() = runTest {
        val transport = FakeTransport(PcSendOutcome.Unreachable("нет связи", PcUnreachable.SERVER_SILENT))

        val reason = reasonOf(PcRealizer(FakeLinks(linked), transport).perform(obj(), null))

        assertEquals("До сервера Point не дозвониться. Проверьте интернет и попробуйте ещё раз.", reason)
    }

    @Test
    fun `упёрлись в размер — сказано словами и с числом`() = runTest {
        val transport = FakeTransport(PcSendOutcome.Unreachable("507", PcUnreachable.TOO_BIG))

        val reason = reasonOf(PcRealizer(FakeLinks(linked), transport).perform(obj(), null))

        assertEquals("Объект больше 50 МБ — столько за раз между устройствами не переслать.", reason)
    }

    @Test
    fun `устройство отключили от аккаунта — зовём войти заново`() = runTest {
        val transport = FakeTransport(PcSendOutcome.Rejected)

        val reason = reasonOf(PcRealizer(FakeLinks(linked), transport).perform(obj(), null))

        assertEquals("Это устройство отключили от аккаунта. Войдите заново.", reason)
    }

    @Test
    fun `каждый отказ поправим — тупика на отправке нет`() = runTest {
        val links = FakeLinks(linked)
        PcUnreachable.values().forEach { why ->
            val transport = FakeTransport(PcSendOutcome.Unreachable("x", why))
            val result = PcRealizer(links, transport).perform(obj(), null)
            assertTrue("$why оставил человека в тупике", (result as ActionResult.Failure).recoverable)
        }
    }

    @Test
    fun `разных слов ровно столько, сколько разных бед`() {

        val said = PcUnreachable.values().map { com.point.core.flow.pcUnreachableText(it) }

        assertEquals(PcUnreachable.values().size, said.toSet().size)
        assertTrue("отказ обязан говорить, что делать", said.all { it.length > 30 })
    }
}
