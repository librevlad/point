package com.point.executors

import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PcCapsStore
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePcActionTest {

    private val action = PcRemoteAction("pc-open", "Открыть на компьютере")

    private val printerAction = PcRemoteAction("pc-print", "Напечатать на ПК")

    private class FakeLinks(var pc: LinkedPc? = LinkedPc("d-pc", "Домашний ПК", "ключ")) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) { this.pc = pc }
        override suspend fun clear() { pc = null }
    }

    private class FakeTransport(var outcome: PcSendOutcome = PcSendOutcome.Sent()) : PcTransport {
        var sentAction: String? = null
        var sent = false
        override suspend fun send(
            pc: LinkedPc,
            obj: PointObject,
            fileName: String,
            meta: Map<String, String>,
            action: String?,
        ): PcSendOutcome {
            sentAction = action
            sent = true
            return outcome
        }
        override suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pc: LinkedPc): List<com.point.core.flow.PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String): Boolean = false
        override suspend fun ackOutbox(pc: LinkedPc, id: Int) {}
        override suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<com.point.core.flow.PcRemoteAction>): Boolean = true
        override suspend fun exchangeSecrets(
            pc: LinkedPc,
            mine: com.point.core.flow.SharedSecrets,
        ): com.point.core.flow.SharedSecrets? = null
    }

    private fun obj() = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    @Test
    fun `видно, только когда компьютер в круге, и никогда — для набора`() {
        val linked = RemotePcCapability(action, FakeLinks())
        assertTrue(linked.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(linked.accepts(ObjectState(ObjectKind.COLLECTION)))
        assertEquals("Открыть на компьютере", linked.label(ObjectState(ObjectKind.TEXT)))

        val alone = RemotePcCapability(action, FakeLinks(pc = null))
        assertFalse(alone.accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `a kind-gated action appears only for its kinds (#80 v2)`() {
        val urlOnly = RemotePcCapability(PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL")), FakeLinks())
        assertTrue(urlOnly.accepts(ObjectState(ObjectKind.URL)))
        assertFalse(urlOnly.accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `объект уезжает с именем действия, но «готово» без исхода не говорится`() = runTest {
        val transport = FakeTransport(PcSendOutcome.Sent())
        val result = RemotePcRealizer(action, FakeLinks(), transport).perform(obj(), null)

        assertEquals("pc-open", transport.sentAction)
        assertEquals("Отправлено на компьютер", (result as ActionResult.Done).message)
        assertFalse("«готово» — слово того, кто это сделал", result.message.contains("готово"))
    }

    @Test
    fun `компьютер сказал, чем кончилось, — человек читает его слова`() = runTest {
        val printed = PcSendOutcome.Sent(PcActionOutcome.Done("В очереди «HP LaserJet» · проверьте принтер"))
        val result = RemotePcRealizer(printerAction, FakeLinks(), FakeTransport(printed)).perform(obj(), null)

        assertEquals("В очереди «HP LaserJet» · проверьте принтер", (result as ActionResult.Done).message)
    }

    @Test
    fun `на компьютере не напечаталось — это отказ, а не «готово»`() = runTest {
        val failed = PcSendOutcome.Sent(PcActionOutcome.Failed("На компьютере сейчас нет принтера по умолчанию"))

        val result = RemotePcRealizer(printerAction, FakeLinks(), FakeTransport(failed)).perform(obj(), null)

        assertTrue("исход действия — исход, а не доставка", result is ActionResult.Failure)
        assertEquals("На компьютере сейчас нет принтера по умолчанию", (result as ActionResult.Failure).reason)
        assertTrue("человек может повторить", result.recoverable)
    }

    @Test
    fun `сделано без слов компьютера — «готово» от имени действия`() = runTest {
        val done = PcSendOutcome.Sent(PcActionOutcome.Done(null))

        val result = RemotePcRealizer(action, FakeLinks(), FakeTransport(done)).perform(obj(), null)

        assertEquals("Открыть на компьютере — готово", (result as ActionResult.Done).message)
    }

    private val printerless = PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = "на компьютере нет принтера")

    @Test
    fun `недоступное действие не становится кнопкой, но объясняет причину`() {
        val cap = RemotePcCapability(printerless, FakeLinks())

        assertFalse("нажать недоступное нельзя", cap.accepts(ObjectState(ObjectKind.PDF)))
        assertEquals("на компьютере нет принтера", cap.missing(ObjectState(ObjectKind.PDF)))
    }

    @Test
    fun `причина молчит там, где кнопки и не было бы`() {

        assertNull(RemotePcCapability(printerless, FakeLinks(pc = null)).missing(ObjectState(ObjectKind.PDF)))

        val urlOnly = PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL"), unavailable = "нет yt-dlp")
        assertNull(RemotePcCapability(urlOnly, FakeLinks()).missing(ObjectState(ObjectKind.IMAGE)))
        assertEquals("нет yt-dlp", RemotePcCapability(urlOnly, FakeLinks()).missing(ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `причины нет — нет и подсказки, но кнопки тоже нет`() {

        val mute = RemotePcCapability(PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = ""), FakeLinks())

        assertNull(mute.missing(ObjectState(ObjectKind.PDF)))
        assertFalse(mute.accepts(ObjectState(ObjectKind.PDF)))
    }

    @Test
    fun `недоступное не уезжает на компьютер даже в обход экрана`() = runTest {

        val transport = FakeTransport()

        val result = RemotePcRealizer(printerless, FakeLinks(), transport).perform(obj(), null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
        assertTrue("причина обязана дойти", result.reason.contains("нет принтера"))
        assertNull("ничего не отправлено", transport.sentAction)
        assertFalse("объект не уехал", transport.sent)
    }

    @Test
    fun `действие компьютера ждёт теми же словами, что и «На компьютер»`() = runTest {

        val heard = stagesHeard { RemotePcRealizer(action, FakeLinks(), FakeTransport()).perform(obj(), null) }

        assertEquals(listOf("Отправляю на компьютер"), heard)
    }

    @Test
    fun `недоступное действие молчит — работы не было`() = runTest {
        val heard = stagesHeard { RemotePcRealizer(printerless, FakeLinks(), FakeTransport()).perform(obj(), null) }

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `понятое компьютером доезжает знанием на исходник, а не только словами`() = runTest {
        // PC2: перенос не теряет знание — Done с той стороны несёт understood-поля,
        // и телефон кладёт их тем же путём Done+findings.
        val transport = FakeTransport(
            PcSendOutcome.Sent(
                action = com.point.core.flow.PcActionOutcome.Done("Нашёл: телефоны — 1"),
                understanding = mapOf(
                    "entity.phone" to "+380671234567",
                    "investigated.pc-entities" to "found",
                ),
            ),
        )

        val result = RemotePcRealizer(action, FakeLinks(), transport).perform(obj(), null)

        val done = result as ActionResult.Done
        assertEquals("Нашёл: телефоны — 1", done.message)
        assertEquals("+380671234567", done.findings!!.metadata["entity.phone"])
        assertEquals("found", done.findings!!.metadata["investigated.pc-entities"])
    }

    @Test
    fun `действие компьютера отказывает теми же словами, что и «На компьютер»`() = runTest {

        val rejected = RemotePcRealizer(action, FakeLinks(), FakeTransport(PcSendOutcome.Rejected))
            .perform(obj(), null)
        assertEquals(
            com.point.core.flow.PC_DEVICE_REVOKED,
            (rejected as ActionResult.Failure).reason,
        )

        val asleep = PcSendOutcome.Unreachable("нет ответа", com.point.core.flow.PcUnreachable.PC_ASLEEP)
        val gone = RemotePcRealizer(action, FakeLinks(), FakeTransport(asleep)).perform(obj(), null)
        assertEquals(
            com.point.core.flow.pcUnreachableText(com.point.core.flow.PcUnreachable.PC_ASLEEP),
            (gone as ActionResult.Failure).reason,
        )
        assertTrue(gone.recoverable)
    }
}
