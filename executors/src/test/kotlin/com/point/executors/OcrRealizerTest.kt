package com.point.executors

import com.point.core.flow.LlmClient
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

class OcrRealizerTest {

    private val image = PointObject(
        id = "id",
        mime = "image/png",
        uri = ScratchRef("/tmp/x.png"),
        state = ObjectState(ObjectKind.IMAGE),
    )

    @Test
    fun `recognised text comes back as a TEXT object`() = runTest {
        val fakeLlm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) =
                ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/out/recognised.md"))
        }
        val result = OcrRealizer(fakeLlm).perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("/out/recognised.md", (result as ActionResult.Success).result.uri.value)
    }

    @Test
    fun `llm failure surfaces as a recoverable error`() = runTest {
        val failing = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject = error("HTTP 429")
        }
        val result = OcrRealizer(failing).perform(image)
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}
