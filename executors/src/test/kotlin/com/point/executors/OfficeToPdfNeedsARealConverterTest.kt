package com.point.executors

import com.point.core.flow.LinkedPc
import com.point.core.flow.NO_OFFICE_ON_PC
import com.point.core.flow.NO_PC_WITH_OFFICE
import com.point.core.flow.OfficeOrgan
import com.point.core.flow.PcCapsStore
import com.point.core.flow.PcLinks
import com.point.core.flow.PcOfficeOrgan
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcTransport
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.capabilities.PdfCapability
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Офис в PDF — только настоящим конвертером, и видно, какого органа не хватает (#403).
 *
 * Телефон превращал офисный файл в PDF пересказом: вынимал текст и печатал заново. Для
 * договора терпимо, для презентации бессмысленно — слайд без картинок, разметки и порядка
 * перестаёт быть слайдом. При этом действие на телефоне и на компьютере называлось одинаково,
 * и человек не знал, что получит.
 */
class OfficeToPdfNeedsARealConverterTest {

    private val office = ObjectState(ObjectKind.OFFICE)

    private val now = 1_700_000_000_000L

    private class Circle(private val pc: LinkedPc?) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private class Told(
        private val caps: List<PcRemoteAction>,
        private val at: Long?,
    ) : PcCapsStore {
        override fun all() = caps
        override suspend fun save(caps: List<PcRemoteAction>) = Unit
        override suspend fun clear() = Unit
        override fun savedAt() = at
    }

    private val home = LinkedPc("pc", "Домашний ПК", "ключ")

    private fun organ(
        pc: LinkedPc? = home,
        pdf: PcRemoteAction? = PcRemoteAction("pdf", "В PDF"),
        at: Long? = now,
    ) = PcOfficeOrgan(Told(listOfNotNull(pdf), at), Circle(pc)) { now }

    // ——— 1. Пересказа на телефоне нет ———

    @Test
    fun `без органа офисный файл в PDF не превращается`() {
        assertFalse(PdfCapability(OfficeOrgan { NO_PC_WITH_OFFICE }).accepts(office))
    }

    @Test
    fun `свой исполнитель за офисный документ не берётся`() {
        val realizer = PdfRealizer(NoStore, NoPdfText, NoPages)

        assertFalse("телефон снова пересказывает документ", realizer.accepts(office))
    }

    // ——— 2. Действие видно с причиной, а не исчезает ———

    @Test
    fun `без органа действие видно и называет причину`() {
        val registry = DefaultCapabilityRegistry(
            capabilities = setOf(PdfCapability(OfficeOrgan { NO_PC_WITH_OFFICE }), OfficeCapability()),
            policy = DefaultBubblePolicy(),
        )

        val latent = registry.latentBubblesFor(office)

        assertEquals(listOf("В PDF"), latent.map { it.title })
        assertEquals(listOf(NO_PC_WITH_OFFICE), latent.map { it.missing })
    }

    @Test
    fun `с органом это обычная кнопка, а не «почти доступно»`() {
        val registry = DefaultCapabilityRegistry(
            capabilities = setOf(PdfCapability(organ()), OfficeCapability()),
            policy = DefaultBubblePolicy(),
        )

        assertTrue("pdf" in registry.bubblesFor(office).map { it.capabilityId.value })
        assertTrue(registry.latentBubblesFor(office).isEmpty())
    }

    // ——— 3. Живой компьютер с офисом делает работу ———

    @Test
    fun `при живом компьютере с офисом работу берёт он`() {
        val remote = remotePcRealizers(
            own = setOf(PdfCapability(organ())),
            fromPc = listOf(PcRemoteAction("pdf", "В PDF")),
            links = Circle(home),
            transport = SilentTransport,
        )
        val chosen = com.point.core.flow.DefaultExecutionPolicy()
            .choose(office, remote.toList() + PdfRealizer(NoStore, NoPdfText, NoPages))

        assertEquals(listOf(PdfCapability.ID), chosen.map { it.capabilityId })
        assertTrue("работу снова взял телефон", chosen.single() is RemotePcRealizer)
    }

    // ——— 4. Причина различает положения человека ———

    @Test
    fun `компьютера нет — так и сказано`() {
        assertEquals(NO_PC_WITH_OFFICE, organ(pc = null).missing())
    }

    @Test
    fun `компьютер есть, офиса на нём нет — это другая причина`() {
        val withoutOffice = PcRemoteAction("pdf", "В PDF", unavailable = "На компьютере нет LibreOffice")

        assertEquals(NO_OFFICE_ON_PC, organ(pdf = withoutOffice).missing())
    }

    @Test
    fun `компьютер с офисом — причины нет`() {
        assertNull(organ().missing())
    }

    /** Устаревшее объявление органом не считается (#633): «офис есть» недельной давности — выдумка. */
    @Test
    fun `протухшее объявление органом не считается`() {
        assertEquals(NO_PC_WITH_OFFICE, organ(at = now - 30L * 24 * 3600 * 1000).missing())
    }

    @Test
    fun `компьютер про такое умение не говорил`() {
        assertEquals(NO_PC_WITH_OFFICE, organ(pdf = null).missing())
    }

    private companion object {

        val NoStore = object : com.point.core.flow.ObjectStore {
            override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
            override suspend fun ingestMultiple(sources: List<String>) = error("unused")
            override suspend fun put(
            result: com.point.core.model.ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
            override suspend fun children(collection: com.point.core.model.PointObject, limit: Int) = error("unused")
            override suspend fun readText(obj: com.point.core.model.PointObject, limit: Int) = error("unused")
            override suspend fun newScratchFile(extension: String) = error("unused")
            override suspend fun clear() = Unit
        }

        val NoPdfText = object : com.point.core.flow.PdfTextExtractor {
            override suspend fun extractText(obj: com.point.core.model.PointObject) = ""
        }

        val SilentTransport = object : PcTransport {
            override suspend fun send(
                pc: LinkedPc,
                obj: com.point.core.model.PointObject,
                name: String,
                understanding: Map<String, String>,
                action: String?,
            ) = com.point.core.flow.PcSendOutcome.Parked

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
}
