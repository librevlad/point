package com.point.executors

import com.point.core.flow.LinkedPc
import com.point.core.flow.ObjectStore
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcReturned
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.SharedSecrets
import com.point.core.flow.PcOutboxEntry
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Конституция §12 и ADR-0001 §20, вариант A (решение владельца, Этап 9):
 * знание с компьютера возвращается ЗНАНИЕМ — `Done.findings` мержит его в исходный
 * объект, а произведённый файл становится новым объектом Graph (found-chip),
 * а не автопереходом. VM-половина пути закрыта тестом Done.findings в FlowViewModelTest.
 */
class PcKnowledgeComesBackTest {

    private val pc = LinkedPc("pc-1", "Рабочий")

    private val page = PointObject(
        id = "page",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/page.jpg"),
        state = ObjectState(ObjectKind.IMAGE),
        metadata = mapOf("name" to "страница.jpg"),
    )

    private class Returning(private val returned: PcReturned?) : PcTransport {
        var sentMeta: Map<String, String> = emptyMap()
        override suspend fun send(
            pc: LinkedPc,
            obj: PointObject,
            fileName: String,
            meta: Map<String, String>,
            action: String?,
        ): PcSendOutcome {
            sentMeta = meta
            return PcSendOutcome.Sent(returned = returned)
        }

        override suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pc: LinkedPc): List<PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String) = false
        override suspend fun ackOutbox(pc: LinkedPc, id: Int) = Unit
        override suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<PcRemoteAction>) = false
        override suspend fun exchangeSecrets(pc: LinkedPc, mine: SharedSecrets): SharedSecrets? = null
    }

    private fun scratch(): ObjectStore = object : ObjectStore {
        override suspend fun newScratchFile(extension: String): ScratchRef {
            val f = File.createTempFile("pc-back-", ".$extension")
            f.deleteOnExit()
            return ScratchRef(f.absolutePath)
        }

        override suspend fun ingest(sourceUri: String, mime: String) = throw UnsupportedOperationException()
        override suspend fun ingestMultiple(sources: List<String>) = throw UnsupportedOperationException()
        override suspend fun put(
            result: com.point.core.model.ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = throw UnsupportedOperationException()
        override suspend fun children(collection: PointObject, limit: Int) =
            throw UnsupportedOperationException()
        override suspend fun readText(obj: PointObject, limit: Int) = ""
        override suspend fun clear() = Unit
    }

    @Test
    fun `knowledge returns as knowledge, the produced file becomes a new graph object`() = runTest {
        val returned = PcReturned(
            name = "страница.txt",
            mime = "text/plain",
            bytes = "текст страницы".toByteArray(),
            understanding = mapOf("entity.phone" to "+380671234567"),
        )
        val realizer = RemotePcRealizer(
            action = PcRemoteAction("read", "Прочитать"),
            links = object : PcLinks {
                override fun current() = pc
                override suspend fun save(pc: LinkedPc) = Unit
                override suspend fun clear() = Unit
            },
            transport = Returning(returned),
            store = scratch(),
        )

        val result = realizer.perform(page, null)

        assertTrue("знание возвращается исходом «выполнено», получено-$result", result is ActionResult.Done)
        val findings = (result as ActionResult.Done).findings!!

        assertEquals("понимание адресовано исходнику", "+380671234567", findings.metadata["entity.phone"])

        val produced = findings.objects.single()
        assertEquals("страница.txt", produced.metadata["name"])
        assertEquals("text/plain", produced.mime)
        assertEquals("текст страницы", File(produced.uri.value).readText())
        assertEquals("результат помнит источник", listOf("page"), produced.sourceObjects)
        assertEquals(
            "результат связан с исходником",
            listOf(produced.id),
            findings.relations.filter { it.toId == "page" }.map { it.fromId },
        )
        assertTrue("в сообщении видно имя результата", result.message.contains("страница.txt"))
    }

    /**
     * Прочитанное компьютером ложится знанием документа здесь (#811, #995).
     *
     * Компьютер читает документ по просьбе телефона и присылает текст значением — ссылка на
     * его диск здесь мертва. Раньше приезжала именно ссылка: документ на телефоне оставался
     * «непрочитанным», и телефон снова звал делать уже сделанное.
     */
    @Test
    fun `текст, прочитанный компьютером, становится знанием документа на телефоне`() = runTest {
        val transport = object : PcTransport by Returning(null) {
            override suspend fun send(
                pc: LinkedPc,
                obj: PointObject,
                fileName: String,
                meta: Map<String, String>,
                action: String?,
            ) = PcSendOutcome.Sent(
                action = com.point.core.flow.PcActionOutcome.Done("Текст прочитан"),
                understanding = mapOf(com.point.core.flow.META_READ_TEXT to "Счёт № 12 на 3480 гривен"),
            )
        }
        val realizer = RemotePcRealizer(
            action = PcRemoteAction("office", "Извлечь текст"),
            links = object : PcLinks {
                override fun current() = pc
                override suspend fun save(pc: LinkedPc) = Unit
                override suspend fun clear() = Unit
            },
            transport = transport,
            store = scratch(),
        )

        val result = realizer.perform(page, null)

        val findings = (result as ActionResult.Done).findings!!
        assertTrue("документ не получил признака «текст есть»", com.point.core.model.Feature.HAS_TEXT in findings.features)
        val ref = findings.metadata[com.point.core.flow.META_OCR_TEXT_REF]
        assertTrue("текста у документа нет: ${findings.metadata}", !ref.isNullOrBlank())
        assertTrue("текст доехал не целиком", File(ref!!).readText().contains("3480"))
        assertTrue(
            "значение осталось притворяться знанием",
            com.point.core.flow.META_READ_TEXT !in findings.metadata,
        )
    }

    @Test
    fun `what the phone already knows travels to the computer with the object`() = runTest {
        val transport = Returning(returned = null)
        val realizer = RemotePcRealizer(
            action = PcRemoteAction("read", "Прочитать"),
            links = object : PcLinks {
                override fun current() = pc
                override suspend fun save(pc: LinkedPc) = Unit
                override suspend fun clear() = Unit
            },
            transport = transport,
            store = scratch(),
        )

        realizer.perform(page.copy(metadata = page.metadata + ("entity.email" to "a@b.c")), null)

        assertEquals("a@b.c", transport.sentMeta["entity.email"])
    }
}
