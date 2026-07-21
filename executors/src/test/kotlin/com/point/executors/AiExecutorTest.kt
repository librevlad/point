package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExecutorTest {

    private val ai = AiExecutor(object : LlmClient {
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
        val result = ai.execute(obj, amendment = null)
        assertTrue(result is ExecutorResult.NeedsInput)
    }
}
