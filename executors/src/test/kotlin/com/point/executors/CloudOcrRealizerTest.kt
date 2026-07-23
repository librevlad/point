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

/**
 * Cloud OCR realizer — the chain's fallback (priority 90) head. Wraps the LLM vision
 * path; a missing key / provider failure surfaces as a **recoverable** Failure, which
 * — as the last link — is what the user sees when nothing recognised the image.
 */
class CloudOcrRealizerTest {

    private fun llm(answer: ResultObject? = null) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject =
            answer ?: error("нет ключа")
    }

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `the LLM result is returned as a success`() = runTest {
        val cloud = ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/out/cloud.md"))
        val result = CloudOcrRealizer(llm(cloud)).perform(image)

        assertTrue(result is ActionResult.Success)
        assertEquals("/out/cloud.md", (result as ActionResult.Success).result.uri.value)
    }

    @Test
    fun `a provider failure surfaces as a recoverable failure`() = runTest {
        val result = CloudOcrRealizer(llm(/* throws */)).perform(image)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }
}
