package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.DropLink
import com.point.core.flow.Entitlements
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.sharedCapabilities
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кто нажал, тот и получает (#1034, #1106, решение владельца 21.08.2026).
 *
 * «Дать ссылку» на телефоне уходила исполняться на компьютер: ссылка ложилась в буфер
 * компьютера, объект целиком уезжал в его папку, а при выключенном компьютере человек
 * вместо ссылки слышал, что файл заберут позже. Просили ссылку здесь — получали доставку
 * туда.
 *
 * Обещание: у способности, чей результат остаётся у исполнителя, соседа в исполнители не
 * берут; свой не смог — называется причина отказа, а не подмена устройства.
 */
class LinkStaysWhereAskedTest {

    @Test fun `компьютер не становится исполнителем «Дать ссылку»`() {

        val ids = remotePcRealizers(phoneOwn, advertisedByPc, pairedPc, WatchfulTransport()).map { it.capabilityId }

        assertFalse("ссылку снова выдаёт сосед- $ids", DropLinkCapability.ID in ids)
    }

    /** Признак общий, а не оговорка про одну кнопку- остальные умения компьютера на месте. */
    @Test fun `остальные умения компьютер по-прежнему исполняет`() {

        val ids = remotePcRealizers(phoneOwn, advertisedByPc, pairedPc, WatchfulTransport()).map { it.capabilityId }

        assertTrue("компьютер перестал быть исполнителем чужого умения- $ids", TranscribeCapability.ID in ids)
    }

    @Test fun `свой исполнитель не смог — называется причина, а не доставка на компьютер`() = runTest {
        val transport = WatchfulTransport()
        val resolver = phoneWithPc(transport)

        val result = resolver.realizerFor(DropLinkCapability.ID, text.state).perform(text)

        assertTrue("вместо отказа человеку снова обещали компьютер- $result", result is ActionResult.Failure)
        assertFalse("объект уехал на компьютер за спиной у просьбы", transport.sent)
        assertTrue(
            "ответ пришёл словами доставки на компьютер",
            (result as ActionResult.Failure).reason != com.point.core.flow.PC_PARKED_TEXT,
        )
    }

    private fun phoneWithPc(transport: PcTransport): DefaultResolver {
        val own: Set<Capability> = phoneOwn
        val registry = DefaultCapabilityRegistry(
            own + remotePcCapabilities(own, advertisedByPc, pairedPc),
            DefaultBubblePolicy(),
        )
        val realizers = setOf<com.point.core.flow.Realizer>(DropLinkRealizer(NoStore, NoServer)) +
            remotePcRealizers(own, advertisedByPc, pairedPc, transport)
        return DefaultResolver(realizers, registry, Entitlements { true })
    }

    private class WatchfulTransport : PcTransport {
        var sent = false
        override suspend fun send(
            pc: LinkedPc,
            obj: PointObject,
            fileName: String,
            meta: Map<String, String>,
            action: String?,
        ): PcSendOutcome {
            sent = true
            return PcSendOutcome.Parked
        }

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

    private companion object {

        val text = PointObject(
            "obj",
            "text/plain",
            ScratchRef("/scratch/obj.txt"),
            ObjectState(ObjectKind.TEXT),
            metadata = mapOf("name" to "проба.txt"),
        )

        val phoneOwn: Set<Capability> = setOf(TranscribeCapability(com.point.core.flow.SpeechReadiness { emptyList() })) +
            sharedCapabilities()

        /** Что объявляет компьютер- «Дать ссылку» он умеет и предлагает её телефону. */
        val advertisedByPc = listOf(
            PcRemoteAction(DropLinkCapability.ID.value, "Дать ссылку"),
            PcRemoteAction("transcribe", "Расшифровать", setOf("AUDIO")),
        )

        val pairedPc = object : PcLinks {
            override fun current() = LinkedPc("d-pc", "Домашний ПК", "ключ")
            override suspend fun save(pc: LinkedPc) = Unit
            override suspend fun clear() = Unit
        }

        /** Сервер Point недостижим- своя попытка честно возвращает пустоту. */
        val NoServer = DropLink { _, _, _ -> null }

        val NoStore = object : com.point.core.flow.ObjectStore {
            override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
            override suspend fun ingestMultiple(sources: List<String>) = error("unused")
            override suspend fun put(
                result: com.point.core.model.ResultObject,
                from: PointObject?,
                by: com.point.core.model.CapabilityId?,
            ) = error("unused")
            override suspend fun children(collection: PointObject, limit: Int) = error("unused")
            override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
            override suspend fun newScratchFile(extension: String) = error("unused")
            override suspend fun clear() = Unit
        }
    }
}
