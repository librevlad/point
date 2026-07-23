package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `the input request carries 3 context suggestions (#86)`() = runTest {
        val result = ai.perform(obj, amendment = null) as ActionResult.NeedsInput
        assertEquals(aiSuggestions(ObjectKind.TEXT), result.suggestions)
        assertEquals(3, result.suggestions.size)
    }

    @Test
    fun `suggestions differ by object kind`() {
        assertNotEquals(aiSuggestions(ObjectKind.IMAGE), aiSuggestions(ObjectKind.PDF))
        assertTrue(aiSuggestions(ObjectKind.IMAGE).isNotEmpty())
    }
}
