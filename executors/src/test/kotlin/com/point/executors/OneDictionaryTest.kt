package com.point.executors

import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.capabilities.sharedCapabilities
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDictionaryTest {

    private class FixedPc(private val pc: LinkedPc?) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private val pc = LinkedPc("d-pc", "Рабочий ноутбук", "ключ")

    private val fromPc = listOf(
        PcRemoteAction("ocr", "Распознать текст", setOf("IMAGE")),
        PcRemoteAction("pc-print", "Напечатать на ПК"),
    )

    private fun phoneRegistry() = DefaultCapabilityRegistry(
        capabilities = setOf(ShareCapability(), SaveCapability()) +
            sharedCapabilities() +
            fromPc
                .filterNot { CapabilityId(it.id) in com.point.core.flow.capabilities.sharedCapabilityIds }
                .map { RemotePcCapability(it, FixedPc(pc)) },
        policy = DefaultBubblePolicy(),
    )

    @Test fun `на снимке одно «Распознать текст», а не два`() {
        val titles = phoneRegistry().bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.title }

        assertEquals(
            "чтение снимка предложено не один раз: $titles",
            1,
            titles.count { it.contains("Распознать") },
        )

        assertTrue(
            "у общего намерения снова появился двойник с устройством: $titles",
            titles.none { it.contains("Распознать") && it.contains("на ПК") },
        )
    }

    @Test fun `реализация компьютера не потерялась — она кандидат к той же способности`() {

        val remote = RemotePcRealizer(fromPc[0], FixedPc(pc), NoTransport)

        assertEquals(OcrCapability.ID, remote.capabilityId)
    }

    @Test fun `непереехавшее объявляется по-старому и остаётся видимым`() {

        val remote = RemotePcRealizer(fromPc[1], FixedPc(pc), NoTransport)

        assertEquals(CapabilityId("pc-do:pc-print"), remote.capabilityId)
        assertTrue(
            "действие компьютера пропало с телефона",
            phoneRegistry().bubblesFor(ObjectState(ObjectKind.IMAGE)).any { it.title == "Напечатать на ПК" },
        )
    }

    @Test fun `в общем словаре нет ни одной способности с устройством в идентификаторе`() {

        val owned = sharedCapabilities().map { it.id.value }.filter { it.startsWith("pc-") }

        assertTrue("намерение снова присвоено устройству: $owned", owned.isEmpty())
    }

    private object NoTransport : com.point.core.flow.PcTransport {
        override suspend fun send(
            pc: LinkedPc,
            obj: com.point.core.model.PointObject,
            name: String,
            meta: Map<String, String>,
            action: String?,
        ) = com.point.core.flow.PcSendOutcome.Unreachable("тест", com.point.core.flow.PcUnreachable.PC_ASLEEP)

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
}
