package com.point.executors

import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.Entitlements
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.TextRecognizer
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrChainTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun recognizer(result: String) = object : TextRecognizer {
        override suspend fun recognize(obj: PointObject) = result
    }

    private class TrackingLlm(private val answer: ResultObject?) : LlmClient {
        var called = false
            private set
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            called = true
            return answer ?: error("нет ключа")
        }
    }

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(OcrCapability()),
        policy = DefaultBubblePolicy(),
    )

    private fun resolver(recognized: String, llm: TrackingLlm) = DefaultResolver(
        realizers = setOf(DeviceOcrRealizer(store, recognizer(recognized)), CloudOcrRealizer(llm, privacyAt())),
        registry = registry,
        entitlements = Entitlements { true },
    )

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `on-device text wins and the cloud is never reached`() = runTest {
        val llm = TrackingLlm(ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/cloud.md")))
        val result = resolver("Текст с устройства", llm).realizerFor(OcrCapability.ID).perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("on-device", (result as ActionResult.Success).result.metadata["engine"])
        assertFalse(llm.called)
    }

    @Test
    fun `a blank on-device result falls through to the cloud`() = runTest {
        val cloud = ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/cloud.md"))
        val llm = TrackingLlm(cloud)
        val result = resolver("   ", llm).realizerFor(OcrCapability.ID).perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("/cloud.md", (result as ActionResult.Success).result.uri.value)
        assertTrue(llm.called)
    }

    @Test
    fun `nothing on-device and no cloud key surfaces a recoverable failure`() = runTest {
        val llm = TrackingLlm(null)
        val result = resolver("", llm).realizerFor(OcrCapability.ID).perform(image)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}
