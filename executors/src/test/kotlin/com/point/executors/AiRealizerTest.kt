package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRealizerTest {

    private val ai = AiRealizer(object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String) = error("must not be called")
    })

    private val obj = PointObject(
        id = "id",
        mime = "text/plain",
        uri = ScratchRef("/tmp/x.txt"),
        state = ObjectState(ObjectKind.TEXT),
    )

    @Test
    fun `first tap asks the user for input instead of calling the LLM`() = runTest {
        val result = ai.perform(obj, amendment = null)
        assertTrue(result is ActionResult.NeedsInput)
    }
}
