package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ValueRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Явное исправление/подтверждение — единственный писатель `Provenance.HUMAN` (ADR-0001 §8).
 * Шаг с вводом идёт существующим NeedsInput-механизмом и завершается знанием, не объектом.
 */
class CorrectValueActionTest {

    private val realizer = CorrectValueRealizer()

    private fun phoneNode(value: String = "111") = PointObject(
        id = "img:phone",
        mime = "text/plain",
        uri = ValueRef(value),
        state = ObjectState(KIND_PHONE),
        metadata = mapOf(
            "entity.phone" to value,
            "entity.phone" + META_SOURCE_SUFFIX to Provenance.OCR.wire,
            com.point.core.flow.META_AT_REGION to "10.0 20.0 210.0 40.0",
        ),
        sourceObjects = listOf("img"),
    )

    @Test
    fun `without input the step waits for the human, offering the current value`() = runTest {
        val result = realizer.perform(phoneNode(), null)

        assertTrue(result is ActionResult.NeedsInput)
        assertEquals(listOf("111"), (result as ActionResult.NeedsInput).suggestions)
    }

    @Test
    fun `a correction returns knowledge with human provenance, not a new object`() = runTest {
        val result = realizer.perform(phoneNode(), "112")

        assertTrue(result is ActionResult.Done)
        val findings = (result as ActionResult.Done).findings!!
        assertEquals("112", findings.metadata["entity.phone"])
        assertEquals(Provenance.HUMAN, provenanceOf(findings.metadata, "entity.phone"))
        assertTrue("исправление — знание, не объект", findings.objects.isEmpty())
    }

    @Test
    fun `a confirmation carries the same value under human provenance`() = runTest {
        val result = realizer.perform(phoneNode(), "111")

        assertTrue(result is ActionResult.Done)
        assertEquals("Подтверждено вами", (result as ActionResult.Done).message)
        assertEquals(Provenance.HUMAN, provenanceOf(result.findings!!.metadata, "entity.phone"))
    }

    @Test
    fun `blank input cancels instead of writing an empty fact`() = runTest {
        assertTrue(realizer.perform(phoneNode(), "   ") is ActionResult.Failure)
    }

    @Test
    fun `an object without a semantic fact has nothing to correct`() = runTest {

        val bare = phoneNode().copy(metadata = mapOf(com.point.core.flow.META_AT_REGION to "0 0 1 1"))

        assertTrue(realizer.perform(bare, "112") is ActionResult.Failure)
    }

    @Test
    fun `the capability offers itself on extracted values only`() {
        val capability = CorrectValueCapability()

        assertTrue(capability.accepts(ObjectState(KIND_PHONE)))
        assertTrue(!capability.accepts(ObjectState(ObjectKind.IMAGE)))
        assertTrue(!capability.accepts(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `localization and annotations are not correctable facts`() {
        assertEquals(
            "entity.phone",
            correctableKey(
                mapOf(
                    com.point.core.flow.META_AT_REGION to "0 0 1 1",
                    "entity.phone.alt" to "x",
                    "entity.phone" to "111",
                ),
            ),
        )
    }
}
