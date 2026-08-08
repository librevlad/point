package com.point.executors

import com.point.core.flow.capabilities.PdfCapability
import com.point.core.flow.capabilities.ArchiveCapability
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.capabilities.ImageCapability
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.Latency
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCapabilityRegistryTest {

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(
            ShareCapability(),
            SaveCapability(),
            SaveAllCapability(),
            ShareAllCapability(),
            MergePdfCapability(),
            ScanPdfCapability(),
            OpenCapability(),
            PdfCapability(),
            PagesCapability(),
            ImageCapability(),
            ArchiveCapability(),
            TranslateCapability(aiKeysReady),
            AiCapability(aiKeysReady),
            OpenUrlCapability(),
            OfficeCapability(),
            ScanCapability(),
            OcrCapability(),
            TranscribeCapability { emptyList() },
        ),
        policy = DefaultBubblePolicy(),
    )

    private fun idsFor(state: ObjectState) = idsFor(registry, state)

    private fun idsFor(registry: DefaultCapabilityRegistry, state: ObjectState) =
        registry.bubblesFor(state).map { it.capabilityId.value }.toSet()

    @Test
    fun `image offers image tools (incl scan and OCR) and the universal ones`() {
        val ids = idsFor(ObjectState(ObjectKind.IMAGE))
        assertTrue(ids.containsAll(setOf("share", "save", "ai", "image", "pdf", "scan", "ocr")))
        assertTrue(setOf("archive", "translate", "office").none { it in ids })
    }

    @Test
    fun `bubble order is deterministic and AI comes last`() {
        val order = registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.capabilityId.value }

        assertEquals(listOf("image", "ocr", "pdf", "scan", "open", "save", "share", "ai"), order)
    }

    @Test
    fun `pdf can be split into a pages collection`() {
        assertTrue("pdf-pages" in idsFor(ObjectState(ObjectKind.PDF)))
        assertFalse("pdf-pages" in idsFor(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `pdf bubble flips its label by direction`() {
        val onPdf = registry.bubblesFor(ObjectState(ObjectKind.PDF)).first { it.capabilityId.value == "pdf" }
        val onImage = registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).first { it.capabilityId.value == "pdf" }
        assertEquals("Извлечь текст", onPdf.title)
        assertEquals("В PDF", onImage.title)
    }

    @Test
    fun `office offers extract-text plus to-PDF plus universal`() {
        val ids = idsFor(ObjectState(ObjectKind.OFFICE))
        assertTrue(ids.containsAll(setOf("office", "pdf", "share", "save", "ai")))
        assertTrue(setOf("translate", "image", "archive").none { it in ids })
    }

    @Test
    fun `голосовое — объект с действиями, а не файл, про который нечего сказать`() {

        val ids = idsFor(ObjectState(ObjectKind.AUDIO))

        assertTrue(ids.containsAll(setOf("transcribe", "share", "save", "open", "ai")))

        assertTrue(setOf("ocr", "scan", "image", "archive", "office", "pdf").none { it in ids })
    }

    @Test
    fun `расшифровка — понимание, и на первый экран она не тащит сеть`() {
        val bubble = registry.bubblesFor(ObjectState(ObjectKind.AUDIO))
            .first { it.capabilityId.value == "transcribe" }

        assertEquals(Intent.UNDERSTAND, bubble.intent)
        assertEquals("сетевое — значит уровень AI, каким бы полезным оно ни было", BubbleTier.AI, bubble.tier)
        assertEquals(ObjectState(ObjectKind.TEXT), bubble.expectedNextState)
    }

    @Test
    fun `open is offered for files but not for urls or collections`() {
        assertTrue("open" in idsFor(ObjectState(ObjectKind.PDF)))
        assertTrue("open" in idsFor(ObjectState(ObjectKind.UNKNOWN)))

        assertFalse("open" in idsFor(ObjectState(ObjectKind.URL)))
        assertFalse("open" in idsFor(ObjectState(ObjectKind.COLLECTION)))
    }

    @Test
    fun `a collection offers save-all and share-all but hides single-object actions`() {
        val ids = idsFor(ObjectState(ObjectKind.COLLECTION))
        assertTrue(ids.containsAll(setOf("save-all", "share-all", "merge-pdf", "scan-pdf")))

        assertTrue(setOf("share", "save", "ai", "open").none { it in ids })
    }

    @Test
    fun `open-url is feature-gated on text but immediate for a uri-list`() {
        assertFalse("open-url" in idsFor(ObjectState(ObjectKind.TEXT)))
        assertTrue("open-url" in idsFor(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_URL))))
        assertTrue("open-url" in idsFor(ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `«Дать ссылку» есть у файла, но не у самой ссылки`() {

        val dropRegistry = DefaultCapabilityRegistry(
            capabilities = setOf(DropLinkCapability(), OpenUrlCapability()),
            policy = DefaultBubblePolicy(),
        )

        assertTrue("drop-link" in idsFor(dropRegistry, ObjectState(ObjectKind.PDF)))
        assertTrue("drop-link" in idsFor(dropRegistry, ObjectState(ObjectKind.AUDIO)))
        assertFalse("drop-link" in idsFor(dropRegistry, ObjectState(ObjectKind.URL)))

        assertTrue("open-url" in idsFor(dropRegistry, ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `результат «Дать ссылку» не годится ей же на вход`() {

        val drop = DropLinkCapability()
        val output = drop.produces(ObjectState(ObjectKind.PDF))

        assertEquals(ObjectState(ObjectKind.URL), output)
        assertFalse("иначе — ссылка на ссылку", drop.accepts(output))
    }

    @Test
    fun `bubble tier derives from meta — cloud is AI, instant local is INSTANT, transforms are SMART`() {
        val bubbles = registry.bubblesFor(ObjectState(ObjectKind.IMAGE))
        fun tier(id: String) = bubbles.first { it.capabilityId.value == id }.tier
        assertEquals(BubbleTier.AI, tier("ai"))
        assertEquals(BubbleTier.INSTANT, tier("share"))
        assertEquals(BubbleTier.SMART, tier("ocr"))
    }

    @Test
    fun `распознавание на устройстве объявлено долгим, а место пузырька не сдвинулось`() {

        assertEquals(Latency.SLOW, OcrCapability().meta.latency)
        assertEquals(
            BubbleTier.SMART,
            registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).first { it.capabilityId.value == "ocr" }.tier,
        )
        assertEquals(
            listOf("image", "ocr", "pdf", "scan", "open", "save", "share", "ai"),
            registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.capabilityId.value },
        )
    }

    @Test
    fun `each bubble carries its capability's primary intent (for grouping on the object screen)`() {
        val bubbles = registry.bubblesFor(ObjectState(ObjectKind.IMAGE))
        fun intent(id: String) = bubbles.first { it.capabilityId.value == id }.intent
        assertEquals(Intent.UNDERSTAND, intent("ocr"))
        assertEquals(Intent.PREPARE, intent("pdf"))
        assertEquals(Intent.OPEN, intent("open"))
        assertEquals(Intent.SEND, intent("share"))
        assertEquals(Intent.UNDERSTAND, intent("ai"))
    }

    @Test
    fun `byId round-trips`() {
        val bubble = registry.bubblesFor(ObjectState(ObjectKind.TEXT)).first()
        assertEquals(bubble.capabilityId, registry.byId(bubble.capabilityId).id)
    }

    @Test
    fun `an image surfaces translate and open-url as almost-available (needs OCR first)`() {
        val latent = registry.latentBubblesFor(ObjectState(ObjectKind.IMAGE))
        assertTrue("Перевести" in latent.map { it.title })
        assertTrue(latent.all { it.missing.contains("распознайте текст") })
        assertTrue("capped so it informs, not clutters", latent.size <= 2)
    }

    @Test
    fun `text has no almost-available translate — it already accepts`() {
        assertFalse("Перевести" in registry.latentBubblesFor(ObjectState(ObjectKind.TEXT)).map { it.title })
    }

    private class FixedPc(private val pc: LinkedPc?) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private val printerless =
        PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = "на компьютере нет принтера")

    private fun registryWithPc(action: PcRemoteAction, pc: LinkedPc?) = DefaultCapabilityRegistry(
        capabilities = setOf(
            ShareCapability(), SaveCapability(), OpenCapability(), PdfCapability(),
            ImageCapability(), TranslateCapability(aiKeysReady), AiCapability(aiKeysReady), OpenUrlCapability(),
            ScanCapability(), OcrCapability(),
            RemotePcCapability(action, FixedPc(pc)),
        ),
        policy = DefaultBubblePolicy(),
    )

    @Test
    fun `причина «нет принтера» не вытесняется повтором чужой подсказки`() {

        val latent = registryWithPc(printerless, LinkedPc("d-pc", "ПК", "ключ"))
            .latentBubblesFor(ObjectState(ObjectKind.IMAGE))

        assertTrue("объяснение компьютера обязано дойти", "Напечатать на ПК" in latent.map { it.title })
        assertEquals("на компьютере нет принтера", latent.first { it.title == "Напечатать на ПК" }.missing)
        assertEquals("два места — два разных объяснения", latent.size, latent.map { it.missing }.toSet().size)
        assertTrue("список не вырос", latent.size <= 2)
    }

    @Test
    fun `есть принтер — обычная кнопка, а не строка с причиной`() {
        val available = PcRemoteAction("pc-print", "Напечатать на ПК")
        val registry = registryWithPc(available, LinkedPc("d-pc", "ПК", "ключ"))

        assertTrue("pc-do:pc-print" in idsFor(registry, ObjectState(ObjectKind.IMAGE)))
        assertFalse(
            "Напечатать на ПК" in registry.latentBubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.title },
        )
    }

    @Test
    fun `без связи с компьютером не появляется ни кнопки, ни причины`() {

        val registry = registryWithPc(printerless, pc = null)
        val state = ObjectState(ObjectKind.IMAGE)

        assertFalse("pc-do:pc-print" in idsFor(registry, state))
        assertFalse("Напечатать на ПК" in registry.latentBubblesFor(state).map { it.title })
    }

    private fun registryWithoutScanPack() = DefaultCapabilityRegistry(
        capabilities = setOf(
            ScanCapability(), ScanPlusCapability(), OcrCapability(), ShareCapability(),
        ),
        policy = DefaultBubblePolicy(),
        availability = { id -> "нужен пакет обработки снимков".takeIf { id == ScanPlusCapability.ID } },
    )

    @Test
    fun `без пака «Скан с цветом» не встаёт в ряд с работающими`() {

        val ids = idsFor(registryWithoutScanPack(), ObjectState(ObjectKind.IMAGE))

        assertFalse("scan-plus" in ids)
        assertTrue("соседей это не трогает", ids.containsAll(setOf("scan", "ocr", "share")))
    }

    @Test
    fun `вместо ложного обещания — строка с причиной`() {

        val latent = registryWithoutScanPack().latentBubblesFor(ObjectState(ObjectKind.IMAGE))

        assertEquals(listOf("Скан с цветом"), latent.map { it.title })
        assertEquals("нужен пакет обработки снимков", latent.single().missing)
    }

    @Test
    fun `недоступное действие не приводит с собой и своё намерение`() {

        val onlyScanPlus = DefaultCapabilityRegistry(
            capabilities = setOf(ScanPlusCapability(), OcrCapability()),
            policy = DefaultBubblePolicy(),
            availability = { "нужен пакет обработки снимков" },
        )

        assertTrue(onlyScanPlus.bubblesFor(ObjectState(ObjectKind.IMAGE)).isEmpty())
    }

    @Test
    fun `есть чем выполнить — обычная строка действия, а не причина`() {
        val ids = idsFor(registry, ObjectState(ObjectKind.IMAGE))
        val latent = registry.latentBubblesFor(ObjectState(ObjectKind.IMAGE))

        assertTrue("scan" in ids)
        assertFalse("Скан" in latent.map { it.title })
    }

    @Test
    fun `an image offers understand, prepare, open and send`() {
        assertEquals(
            setOf(Intent.UNDERSTAND, Intent.PREPARE, Intent.OPEN, Intent.SEND),
            registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.intent }.toSet(),
        )
    }

    @Test
    fun `a collection has prepare and send but nothing to understand`() {
        assertEquals(
            setOf(Intent.PREPARE, Intent.SEND),
            registry.bubblesFor(ObjectState(ObjectKind.COLLECTION)).map { it.intent }.toSet(),
        )
    }

    @Test
    fun `pdf capability understands a PDF but prepares from an image`() {
        val pdf = registry.byId(CapabilityId("pdf"))
        assertEquals(setOf(Intent.UNDERSTAND), pdf.intents(ObjectState(ObjectKind.PDF)))
        assertEquals(setOf(Intent.PREPARE), pdf.intents(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `intent derives from produces — ai understands, share sends, open opens`() {

        assertEquals(setOf(Intent.UNDERSTAND), AiCapability(aiKeysReady).intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.SEND), ShareCapability().intents(ObjectState(ObjectKind.IMAGE)))

        assertEquals(setOf(Intent.OPEN), OpenCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.OPEN), OpenUrlCapability().intents(ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `a same-kind transform prepares, not sends — scan and compress a photo (issue 33)`() {

        assertEquals(setOf(Intent.PREPARE), ScanCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.PREPARE), ImageCapability().intents(ObjectState(ObjectKind.IMAGE)))

        assertEquals(setOf(Intent.SEND), ShareCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.SEND), SaveCapability().intents(ObjectState(ObjectKind.IMAGE)))
    }
}
