package com.point.executors

import com.point.core.flow.LinkedPc
import com.point.core.flow.ObjectStore
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcReturned
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResultComesBackTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("не нужен")
        override suspend fun ingestMultiple(sources: List<String>) = error("не нужен")
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("не нужен")
        override suspend fun children(collection: PointObject, limit: Int) = error("не нужен")
        override suspend fun readText(obj: PointObject, limit: Int) = error("не нужен")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private val pc = LinkedPc("d-pc", "Рабочий ноутбук", "ключ")

    private object Linked : PcLinks {
        override fun current() = LinkedPc("d-pc", "Рабочий ноутбук", "ключ")
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private class Answering(private val outcome: PcSendOutcome) : PcTransport {
        override suspend fun send(
            pc: LinkedPc,
            obj: PointObject,
            name: String,
            meta: Map<String, String>,
            action: String?,
        ) = outcome

        override suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pc: LinkedPc): List<com.point.core.flow.PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String) = false
        override suspend fun ackOutbox(pc: LinkedPc, id: Int) = Unit
        override suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<PcRemoteAction>) = false
        override suspend fun exchangeSecrets(
            pc: LinkedPc,
            mine: com.point.core.flow.SharedSecrets,
        ): com.point.core.flow.SharedSecrets? = null
    }

    private fun snapshot() = PointObject(
        id = "obj",
        mime = "image/png",
        uri = ScratchRef("не читается"),
        state = ObjectState(ObjectKind.IMAGE),
    )

    // Переписан по решению владельца (Этап 9, вариант A): раньше тест фиксировал
    // Success-автопереход, и понимание жило на результате. Теперь знание возвращается
    // исходнику через Done.findings, а результат — новый объект Graph.
    @Test fun `компьютер вернул текст — он становится объектом здесь, а знание — исходнику`() = runTest {
        val returned = PcReturned(
            name = "Текст со снимка",
            mime = "text/plain",
            bytes = "Счёт 4512, оплатить до 20 сентября".toByteArray(Charsets.UTF_8),
            understanding = mapOf("entity.amount" to "4512"),
        )
        val realizer = RemotePcRealizer(
            PcRemoteAction("ocr", "Распознать текст"),
            Linked,
            Answering(PcSendOutcome.Sent(returned = returned)),
            store,
        )

        val result = realizer.perform(snapshot(), null)

        assertTrue("работа снова кончилась словом: $result", result is ActionResult.Done)
        val findings = (result as ActionResult.Done).findings!!
        val born = findings.objects.single()
        assertEquals("Текст со снимка", born.metadata["name"])
        assertEquals("Счёт 4512, оплатить до 20 сентября", File(born.uri.value).readText())

        assertEquals("понимание адресовано исходнику", "4512", findings.metadata["entity.amount"])
        assertEquals("результат помнит источник", listOf("obj"), born.sourceObjects)
    }

    @Test fun `компьютер вернул только слово — говорим словом, как раньше`() = runTest {

        val realizer = RemotePcRealizer(
            PcRemoteAction("pc-print", "Напечатать на ПК"),
            Linked,
            Answering(PcSendOutcome.Sent(action = com.point.core.flow.PcActionOutcome.Done("В очереди «HP»"))),
            store,
        )

        val result = realizer.perform(snapshot(), null)

        assertEquals("В очереди «HP»", (result as ActionResult.Done).message)
    }

    @Test fun `без приёмника объект не выдумывается — человек читает прежние слова`() = runTest {

        val realizer = RemotePcRealizer(
            PcRemoteAction("ocr", "Распознать текст"),
            Linked,
            Answering(PcSendOutcome.Sent(returned = PcReturned("Текст", "text/plain", ByteArray(3)))),
            store = null,
        )

        val result = realizer.perform(snapshot(), null)

        assertTrue("объект родился там, где его некуда деть", result !is ActionResult.Success)
    }
}
