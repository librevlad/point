package com.point.executors

import com.point.core.flow.PcPairing
import com.point.core.flow.PcPairings
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

/**
 * Registry + Bubble Policy are pure logic tested on the JVM. Capabilities are
 * declarations with NO dependencies, so no fakes are needed here (behaviour lives
 * in the realizers, tested separately).
 */
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
            TranslateCapability(),
            AiCapability(),
            OpenUrlCapability(),
            OfficeCapability(),
            ScanCapability(),
            OcrCapability(),
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
        // priority 50 ties by id (image/ocr/pdf/scan), then open(65), save(70), share(80), ai(100)
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
    fun `open is offered for files but not for urls or collections`() {
        assertTrue("open" in idsFor(ObjectState(ObjectKind.PDF)))
        assertTrue("open" in idsFor(ObjectState(ObjectKind.UNKNOWN)))
        // URL has its own «Открыть ссылку»; a collection is a directory — no external viewer.
        assertFalse("open" in idsFor(ObjectState(ObjectKind.URL)))
        assertFalse("open" in idsFor(ObjectState(ObjectKind.COLLECTION)))
    }

    @Test
    fun `a collection offers save-all and share-all but hides single-object actions`() {
        val ids = idsFor(ObjectState(ObjectKind.COLLECTION))
        assertTrue(ids.containsAll(setOf("save-all", "share-all", "merge-pdf", "scan-pdf")))
        // Single-object Share / Save / AI / Open must not target a collection.
        assertTrue(setOf("share", "save", "ai", "open").none { it in ids })
    }

    @Test
    fun `open-url is feature-gated on text but immediate for a uri-list`() {
        assertFalse("open-url" in idsFor(ObjectState(ObjectKind.TEXT)))
        assertTrue("open-url" in idsFor(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_URL))))
        assertTrue("open-url" in idsFor(ObjectState(ObjectKind.URL)))
    }

    // --- Bubble tiers (#114): the visual level derives from the capability's meta ---

    @Test
    fun `bubble tier derives from meta — cloud is AI, instant local is INSTANT, transforms are SMART`() {
        val bubbles = registry.bubblesFor(ObjectState(ObjectKind.IMAGE))
        fun tier(id: String) = bubbles.first { it.capabilityId.value == id }.tier
        assertEquals(BubbleTier.AI, tier("ai"))          // network → AI, whatever else it says
        assertEquals(BubbleTier.INSTANT, tier("share"))  // local + instant latency
        assertEquals(BubbleTier.SMART, tier("ocr"))      // real on-device work (FAST/SLOW)
    }

    @Test
    fun `распознавание на устройстве объявлено долгим, а место пузырька не сдвинулось`() {
        // #288: у чтения страницы бюджет в три минуты, и FAST было прямой неправдой — из-за неё
        // работа шла на объекте, без единого слова о себе и без кнопки отмены. Второе утверждение
        // важнее первого: место пузырька считается по INSTANT против «всего остального», поэтому
        // правка обязана оставить первый экран прежним.
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
        assertEquals(Intent.UNDERSTAND, intent("ocr"))  // recognise text → understand
        assertEquals(Intent.PREPARE, intent("pdf"))     // image → PDF → prepare
        assertEquals(Intent.OPEN, intent("open"))       // open in an app → open
        assertEquals(Intent.SEND, intent("share"))      // share → send
        assertEquals(Intent.UNDERSTAND, intent("ai"))   // ask AI → understand
    }

    @Test
    fun `byId round-trips`() {
        val bubble = registry.bubblesFor(ObjectState(ObjectKind.TEXT)).first()
        assertEquals(bubble.capabilityId, registry.byId(bubble.capabilityId).id)
    }

    // --- Negotiation: "почти доступно" (#97) ---

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

    // --- #316: у компьютера есть действие, но сейчас оно недоступно ---

    private class FixedPairing(private val pairing: PcPairing?) : PcPairings {
        override fun current() = pairing
        override suspend fun save(pairing: PcPairing) = Unit
        override suspend fun clear() = Unit
    }

    private val printerless =
        PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = "на компьютере нет принтера")

    /** Реестр из тех же деклараций плюс одно действие связанного компьютера. */
    private fun registryWithPc(action: PcRemoteAction, pairing: PcPairing?) = DefaultCapabilityRegistry(
        capabilities = setOf(
            ShareCapability(), SaveCapability(), OpenCapability(), PdfCapability(),
            ImageCapability(), TranslateCapability(), AiCapability(), OpenUrlCapability(),
            ScanCapability(), OcrCapability(),
            RemotePcCapability(action, FixedPairing(pairing)),
        ),
        policy = DefaultBubblePolicy(),
    )

    @Test
    fun `причина «нет принтера» не вытесняется повтором чужой подсказки`() {
        // Фото: «Открыть ссылку» и «Перевести» просят одного и того же — распознать текст. Это
        // одна новость; занимая оба места, она молча съедала объяснение компьютера, и человек
        // снова читал пустоту как «Point не умеет печатать».
        val latent = registryWithPc(printerless, PcPairing("h", 1, "tok"))
            .latentBubblesFor(ObjectState(ObjectKind.IMAGE))

        assertTrue("объяснение компьютера обязано дойти", "Напечатать на ПК" in latent.map { it.title })
        assertEquals("на компьютере нет принтера", latent.first { it.title == "Напечатать на ПК" }.missing)
        assertEquals("два места — два разных объяснения", latent.size, latent.map { it.missing }.toSet().size)
        assertTrue("список не вырос", latent.size <= 2)
    }

    @Test
    fun `есть принтер — обычная кнопка, а не строка с причиной`() {
        val available = PcRemoteAction("pc-print", "Напечатать на ПК")
        val registry = registryWithPc(available, PcPairing("h", 1, "tok"))

        assertTrue("pc-do:pc-print" in idsFor(registry, ObjectState(ObjectKind.IMAGE)))
        assertFalse(
            "Напечатать на ПК" in registry.latentBubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.title },
        )
    }

    @Test
    fun `без связи с компьютером не появляется ни кнопки, ни причины`() {
        // Иначе список замусорился бы действиями несуществующего компьютера: «нет принтера» у
        // того, кого нет. Про сам компьютер скажет «На компьютер · подключите компьютер».
        val registry = registryWithPc(printerless, pairing = null)
        val state = ObjectState(ObjectKind.IMAGE)

        assertFalse("pc-do:pc-print" in idsFor(registry, state))
        assertFalse("Напечатать на ПК" in registry.latentBubblesFor(state).map { it.title })
    }

    // --- Intent layer (Object → Intent → … → Object) ---

    @Test
    fun `an image offers understand, prepare, open and send`() {
        assertEquals(
            listOf(Intent.UNDERSTAND, Intent.PREPARE, Intent.OPEN, Intent.SEND),
            registry.intentsFor(ObjectState(ObjectKind.IMAGE)),
        )
    }

    @Test
    fun `a collection has prepare and send but nothing to understand`() {
        assertEquals(
            listOf(Intent.PREPARE, Intent.SEND),
            registry.intentsFor(ObjectState(ObjectKind.COLLECTION)),
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
        // AI produces an unknown object -> UNDERSTAND; a terminal (produces === state) -> SEND.
        assertEquals(setOf(Intent.UNDERSTAND), AiCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.SEND), ShareCapability().intents(ObjectState(ObjectKind.IMAGE)))
        // «Открыть» is its own goal, not «Отправить» (#42).
        assertEquals(setOf(Intent.OPEN), OpenCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.OPEN), OpenUrlCapability().intents(ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `a same-kind transform prepares, not sends — scan and compress a photo (issue 33)`() {
        // #33: scan/compress produce a *fresh* image (same kind), so the naive
        // `produces == state` wrongly bucketed them under SEND and "Скан" disappeared
        // from "Подготовить". A terminal returns the *same* state object; a transform a
        // new one — so intent splits on identity, not value.
        assertEquals(setOf(Intent.PREPARE), ScanCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.PREPARE), ImageCapability().intents(ObjectState(ObjectKind.IMAGE)))
        // terminals on the same image still SEND
        assertEquals(setOf(Intent.SEND), ShareCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.SEND), SaveCapability().intents(ObjectState(ObjectKind.IMAGE)))
    }
}
