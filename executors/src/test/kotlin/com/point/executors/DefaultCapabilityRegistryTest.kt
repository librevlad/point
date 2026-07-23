package com.point.executors

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

    private fun idsFor(state: ObjectState) =
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

    @Test
    fun `byId round-trips`() {
        val bubble = registry.bubblesFor(ObjectState(ObjectKind.TEXT)).first()
        assertEquals(bubble.capabilityId, registry.byId(bubble.capabilityId).id)
    }

    // --- Intent layer (Object → Intent → … → Object) ---

    @Test
    fun `an image offers all three intents`() {
        assertEquals(
            listOf(Intent.UNDERSTAND, Intent.PREPARE, Intent.SEND),
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
    fun `default intent derives from produces — ai understands, share sends`() {
        // AI produces an unknown object -> UNDERSTAND; a terminal (produces === state) -> SEND.
        assertEquals(setOf(Intent.UNDERSTAND), AiCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.SEND), ShareCapability().intents(ObjectState(ObjectKind.IMAGE)))
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
