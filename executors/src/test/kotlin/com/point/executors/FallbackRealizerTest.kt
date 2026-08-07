package com.point.executors

import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
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

class FallbackRealizerTest {

    private val cap = CapabilityId("ocr")
    private fun obj() = PointObject("x", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    private class Probe(private val result: ActionResult) : Realizer {
        override val capabilityId = CapabilityId("ocr")
        var called = false
            private set
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            called = true
            return result
        }
    }

    private fun success(tag: String) =
        ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef("/$tag")))

    private fun chain(vararg probes: Probe) = FallbackRealizer(cap, probes.toList())

    @Test
    fun `first success wins and the next realizer is never called`() = runTest {
        val first = Probe(success("first"))
        val second = Probe(success("second"))
        val result = chain(first, second).perform(obj())
        assertEquals("/first", (result as ActionResult.Success).result.uri.value)
        assertTrue(first.called)
        assertFalse(second.called)
    }

    @Test
    fun `a recoverable failure defers to the next realizer`() = runTest {
        val first = Probe(ActionResult.Failure("miss", recoverable = true))
        val second = Probe(success("second"))
        val result = chain(first, second).perform(obj())
        assertEquals("/second", (result as ActionResult.Success).result.uri.value)
        assertTrue(first.called)
        assertTrue(second.called)
    }

    @Test
    fun `when every realizer defers the last result is returned`() = runTest {
        val first = Probe(ActionResult.Failure("miss-1", recoverable = true))
        val second = Probe(ActionResult.Failure("miss-2", recoverable = true))
        val result = chain(first, second).perform(obj())
        assertEquals("miss-2", (result as ActionResult.Failure).reason)
        assertTrue(second.called)
    }

    @Test
    fun `a non-recoverable failure stops the chain`() = runTest {
        val first = Probe(ActionResult.Failure("fatal", recoverable = false))
        val second = Probe(success("second"))
        val result = chain(first, second).perform(obj())
        assertEquals("fatal", (result as ActionResult.Failure).reason)
        assertFalse(second.called)
    }

    @Test
    fun `a terminal Done stops the chain`() = runTest {
        val first = Probe(ActionResult.Done("done"))
        val second = Probe(success("second"))
        val result = chain(first, second).perform(obj())
        assertEquals("done", (result as ActionResult.Done).message)
        assertFalse(second.called)
    }

    @Test
    fun `NeedsInput stops the chain`() = runTest {
        val first = Probe(ActionResult.NeedsInput("prompt"))
        val second = Probe(success("second"))
        val result = chain(first, second).perform(obj())
        assertEquals("prompt", (result as ActionResult.NeedsInput).prompt)
        assertFalse(second.called)
    }
}
