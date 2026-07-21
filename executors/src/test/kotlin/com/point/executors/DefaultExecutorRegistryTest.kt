package com.point.executors

import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.Exporter
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.OfficeTextExtractor
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.Sharer
import com.point.core.flow.UrlOpener
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is pure logic — tested on the JVM with real executors (fed fakes
 * for their side-effect dependencies), no device. Only accepts/produces/byId are
 * exercised here; the fakes are never invoked.
 */
class DefaultExecutorRegistryTest {

    private val fakeStore = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun newScratchFile(extension: String) = ScratchRef("/tmp/x.$extension")
        override suspend fun clear() = Unit
    }
    private val fakeSharer = object : Sharer {
        override suspend fun share(obj: PointObject) = Unit
    }
    private val fakeExporter = object : Exporter {
        override suspend fun export(obj: PointObject) = "Downloads/x"
    }
    private val fakeLlm = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String) = error("unused")
    }
    private val fakeOpener = object : UrlOpener {
        override suspend fun open(url: String) = Unit
    }
    private val fakePdfText = object : PdfTextExtractor {
        override suspend fun extractText(obj: PointObject) = ""
    }
    private val fakeOfficeText = object : OfficeTextExtractor {
        override suspend fun extractText(obj: PointObject) = ""
    }
    private val fakeArchive = object : ArchiveExtractor {
        override suspend fun extract(obj: PointObject) = 0
    }

    private val registry = DefaultExecutorRegistry(
        setOf(
            ShareExecutor(fakeSharer),
            SaveExecutor(fakeExporter),
            PdfExecutor(fakeStore, fakePdfText),
            ImageExecutor(fakeStore),
            ZipExecutor(fakeArchive),
            TranslateExecutor(fakeLlm, fakePdfText),
            AiExecutor(fakeLlm),
            OpenUrlExecutor(fakeOpener),
            OfficeExecutor(fakeStore, fakeOfficeText),
        ),
    )

    private fun idsFor(state: ObjectState) =
        registry.bubblesFor(state).map { it.executorId.value }.toSet()

    @Test
    fun `image state offers image-capable bubbles and always the universal ones`() {
        val ids = registry.bubblesFor(ObjectState(ObjectKind.IMAGE))
            .map { it.executorId.value }
            .toSet()

        assertTrue(ids.containsAll(setOf("share", "save", "ai")))
        assertTrue(ids.containsAll(setOf("image", "pdf")))
        assertTrue(setOf("zip", "translate").none { it in ids })
    }

    @Test
    fun `pdf bubble flips its label by direction`() {
        val onPdf = registry.bubblesFor(ObjectState(ObjectKind.PDF)).first { it.executorId.value == "pdf" }
        val onImage = registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).first { it.executorId.value == "pdf" }
        assertEquals("Извлечь текст", onPdf.title)
        assertEquals("В PDF", onImage.title)
    }

    @Test
    fun `byId round-trips`() {
        val bubble = registry.bubblesFor(ObjectState(ObjectKind.TEXT)).first()
        assertEquals(bubble.executorId, registry.byId(bubble.executorId).id)
    }

    @Test
    fun `office offers extract-text plus the universal actions`() {
        val ids = idsFor(ObjectState(ObjectKind.OFFICE))
        assertTrue(ids.containsAll(setOf("office", "share", "save", "ai")))
        // Text-only actions are not offered until text is extracted.
        assertTrue(setOf("translate", "pdf", "image", "zip").none { it in ids })
    }

    @Test
    fun `open-url is feature-gated on text but immediate for a uri-list`() {
        // Progressive disclosure: plain text -> no open-url until enrichment adds HAS_URL.
        assertFalse("open-url" in idsFor(ObjectState(ObjectKind.TEXT)))
        assertTrue("open-url" in idsFor(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_URL))))
        // A uri-list is a URL outright.
        assertTrue("open-url" in idsFor(ObjectState(ObjectKind.URL)))
    }
}
