package com.point.executors

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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrRealizerTest {

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun put(result: ResultObject) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("point-", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun clear() = Unit
    }

    private fun recognizer(result: String) = object : TextRecognizer {
        override suspend fun recognize(obj: PointObject) = result
    }

    private fun llm(answer: ResultObject? = null) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject =
            answer ?: error("LLM must not be called")
    }

    private val image = PointObject(
        id = "id",
        mime = "image/png",
        uri = ScratchRef("/tmp/x.png"),
        state = ObjectState(ObjectKind.IMAGE),
    )

    @Test
    fun `on-device text is used directly and the cloud is never called`() = runTest {
        val realizer = OcrRealizer(store, recognizer("Привет из Tesseract"), llm(/* throws if called */))
        val result = realizer.perform(image)

        assertTrue(result is ActionResult.Success)
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.TEXT, out.type)
        assertEquals("on-device", out.metadata["engine"])
        assertEquals("Привет из Tesseract", File(out.uri.value).readText())
    }

    @Test
    fun `blank on-device result falls back to the cloud LLM`() = runTest {
        val cloud = ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/out/cloud.md"))
        val realizer = OcrRealizer(store, recognizer("   "), llm(cloud))
        val result = realizer.perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("/out/cloud.md", (result as ActionResult.Success).result.uri.value)
    }

    @Test
    fun `nothing on-device and no cloud key surfaces a recoverable error`() = runTest {
        val failingLlm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("нет ключа")
        }
        val realizer = OcrRealizer(store, recognizer(""), failingLlm)
        val result = realizer.perform(image)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}
