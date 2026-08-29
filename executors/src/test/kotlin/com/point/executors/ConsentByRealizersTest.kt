package com.point.executors

import org.junit.Assert.assertEquals
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentByRealizersTest {

    private class Cap(id: String) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override fun label(state: ObjectState) = "тест"
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private class R(id: String, kind: RealizerKind) : Realizer {
        override val capabilityId = CapabilityId(id)
        override val meta = RealizerMeta(kind = kind)
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
            ActionResult.Done("ok")
    }

    private fun resolver(vararg realizers: Realizer): DefaultResolver {
        val caps = realizers.mapTo(mutableSetOf<Capability>()) { Cap(it.capabilityId.value) }
        val registry = DefaultCapabilityRegistry(caps, DefaultBubblePolicy())
        return DefaultResolver(realizers.toSet(), registry)
    }

    @Test
    fun `локальная цепочка объект не увозит`() {
        val r = resolver(R("ocr", RealizerKind.LOCAL))

        assertFalse(r.leavesDevice(CapabilityId("ocr")))
    }

    @Test
    fun `один облачный запасной в цепочке — уже увозит`() {

        val r = resolver(R("ocr", RealizerKind.LOCAL), R("ocr", RealizerKind.CLOUD))

        assertTrue(r.leavesDevice(CapabilityId("ocr")))
    }

    @Test
    fun `своё устройство круга — не уход, и согласия не требует`() {

        val r = resolver(R("pc", RealizerKind.REMOTE))

        assertFalse(r.leavesDevice(CapabilityId("pc")))
    }

    /**
     * Согласие спрашивается по объявленному уходу наружу, а не по виду исполнителя (#1088):
     * компьютер остаётся своим вторым устройством в обоих случаях.
     */
    @Test
    fun `компьютер сказал, что увезёт наружу — телефон спросит согласие до отправки`() {
        val outward = RemotePcRealizer(
            PcRemoteAction("pc-ocr", "Прочитать на ПК", leavesCircle = true),
            NoLinks,
            NoTransport,
        )
        val inward = RemotePcRealizer(PcRemoteAction("pc-print", "Напечатать на ПК"), NoLinks, NoTransport)

        assertTrue(resolver(outward).leavesDevice(outward.capabilityId))
        assertFalse(resolver(inward).leavesDevice(inward.capabilityId))

        assertEquals(RealizerKind.REMOTE, outward.meta.kind)
        assertEquals(RealizerKind.REMOTE, inward.meta.kind)
    }

    private object NoLinks : com.point.core.flow.PcLinks {
        override fun current(): com.point.core.flow.LinkedPc? = null
        override suspend fun save(pc: com.point.core.flow.LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private object NoTransport : com.point.core.flow.PcTransport {
        override suspend fun send(
            pc: com.point.core.flow.LinkedPc,
            obj: com.point.core.model.PointObject,
            name: String,
            meta: Map<String, String>,
            action: String?,
        ) = com.point.core.flow.PcSendOutcome.Unreachable("тест", com.point.core.flow.PcUnreachable.PC_ASLEEP)

        override suspend fun fetchCaps(pc: com.point.core.flow.LinkedPc): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pc: com.point.core.flow.LinkedPc): List<com.point.core.flow.PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pc: com.point.core.flow.LinkedPc, id: Int, targetPath: String) = false
        override suspend fun ackOutbox(pc: com.point.core.flow.LinkedPc, id: Int) = Unit
        override suspend fun pushPhoneCaps(pc: com.point.core.flow.LinkedPc, caps: List<PcRemoteAction>) = false
        override suspend fun exchangeSecrets(
            pc: com.point.core.flow.LinkedPc,
            mine: com.point.core.flow.SharedSecrets,
        ): com.point.core.flow.SharedSecrets? = null
    }

    @Test
    fun `неизвестная способность не объявляется увозящей на пустом месте`() {
        val r = resolver(R("ocr", RealizerKind.LOCAL))

        assertFalse(r.leavesDevice(CapabilityId("нет-такой")))
    }
}
