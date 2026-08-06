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

/** Remote PC capabilities (#80): the PC's own actions appear as phone bubbles and run there. */
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

    /**
     * Доставка — это доставка, а не выполнение (#114).
     *
     * Прежний тест на этом месте утверждал `Done` сразу после 200 на доставку файла и назывался
     * «reports success» — он закреплял ошибку: телефон объявлял «готово» про работу, исхода
     * которой не знал. Судить надо обещание, данное человеку, а не форму ответа транспорта.
     */
    @Test
    fun `объект уезжает с именем действия, но «готово» без исхода не говорится`() = runTest {
        val transport = FakeTransport(PcSendOutcome.Sent()) // компьютер об исходе смолчал
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

    /** Компьютер сказал «сделано», но словами не отчитался — тогда говорим мы, и только тогда. */
    @Test
    fun `сделано без слов компьютера — «готово» от имени действия`() = runTest {
        val done = PcSendOutcome.Sent(PcActionOutcome.Done(null))

        val result = RemotePcRealizer(action, FakeLinks(), FakeTransport(done)).perform(obj(), null)

        assertEquals("Открыть на компьютере — готово", (result as ActionResult.Done).message)
    }

    // --- #316: компьютер объявил действие недоступным с причиной ---

    private val printerless = PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = "на компьютере нет принтера")

    @Test
    fun `недоступное действие не становится кнопкой, но объясняет причину`() {
        val cap = RemotePcCapability(printerless, FakeLinks())

        assertFalse("нажать недоступное нельзя", cap.accepts(ObjectState(ObjectKind.PDF)))
        assertEquals("на компьютере нет принтера", cap.missing(ObjectState(ObjectKind.PDF)))
    }

    @Test
    fun `причина молчит там, где кнопки и не было бы`() {
        // Компьютера в круге нет — про его принтер человеку сейчас знать незачем: экран
        // и так скажет, что войти надо на компьютере (PcCapability.missing).
        assertNull(RemotePcCapability(printerless, FakeLinks(pc = null)).missing(ObjectState(ObjectKind.PDF)))

        // Действие про URL — на картинке оно не появилось бы и доступным.
        val urlOnly = PcRemoteAction("pc-download", "Скачать видео на ПК", kinds = setOf("URL"), unavailable = "нет yt-dlp")
        assertNull(RemotePcCapability(urlOnly, FakeLinks()).missing(ObjectState(ObjectKind.IMAGE)))
        assertEquals("нет yt-dlp", RemotePcCapability(urlOnly, FakeLinks()).missing(ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `причины нет — нет и подсказки, но кнопки тоже нет`() {
        // «Недоступно, причина не названа»: выдумывать за компьютер текст мы не будем,
        // а тапнуть всё равно нельзя.
        val mute = RemotePcCapability(PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = ""), FakeLinks())

        assertNull(mute.missing(ObjectState(ObjectKind.PDF)))
        assertFalse(mute.accepts(ObjectState(ObjectKind.PDF)))
    }

    @Test
    fun `недоступное не уезжает на компьютер даже в обход экрана`() = runTest {
        // Сохранённая цепочка или протухший кэш действий ПК могут дойти до реализатора
        // мимо пузырьков — объект обязан остаться на телефоне, а человек получить причину.
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
        // Одна работа — одни слова (#288): байты едут той же дорогой, и разная разговорчивость
        // двух соседних пузырьков читалась бы как «этот завис».
        val heard = stagesHeard { RemotePcRealizer(action, FakeLinks(), FakeTransport()).perform(obj(), null) }

        assertEquals(listOf("Отправляю на компьютер"), heard)
    }

    @Test
    fun `недоступное действие молчит — работы не было`() = runTest {
        val heard = stagesHeard { RemotePcRealizer(printerless, FakeLinks(), FakeTransport()).perform(obj(), null) }

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `действие компьютера отказывает теми же словами, что и «На компьютер»`() = runTest {
        // Шесть формулировок на три события (#524) родились ровно так: у каждого действия были
        // свои слова об одном и том же. Соседние отказы обязаны совпадать дословно.
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
