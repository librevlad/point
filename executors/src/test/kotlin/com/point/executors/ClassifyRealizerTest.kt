package com.point.executors

import com.point.core.flow.CLASSIFIER_NOTHING
import com.point.core.flow.LlmClient
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The classifier end to end (#222, шаг 6): what leaves the device, and what a bad answer costs.
 *
 * The behaviour worth guarding is the refusal. A model that invents `P99` must change nothing at
 * all — no object, no metadata, no quiet fallback to its prose.
 */
class ClassifyRealizerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var lastPrompt: String

    private fun llmAnswering(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastPrompt = prompt
            val f = File(tmp.root, "answer.txt").apply { writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private fun document(): PointObject {
        val f = File(tmp.root, "cmr.txt").apply {
            writeText("Нова Пошта\nТОВ «Агротрейд»\n20 4514 9154 9395")
        }
        return PointObject("cmr", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `a good answer writes the roles onto the object`() = runTest {
        val result = ClassifyRealizer(llmAnswering("carrier=P1\nsender=P2")).perform(document(), null)

        val meta = (result as ActionResult.Success).result.metadata
        assertEquals("Нова Пошта", meta[META_GRAPH_ROLE_PREFIX + "carrier"])
        assertEquals("ТОВ «Агротрейд»", meta[META_GRAPH_ROLE_PREFIX + "sender"])
    }

    @Test
    fun `the value written down is the element's own text, never the model's wording`() = runTest {
        // The model points; the code copies. Even if it paraphrased, the document wins.
        val result = ClassifyRealizer(llmAnswering("sender=P2")).perform(document(), null)

        assertEquals(
            "ТОВ «Агротрейд»",
            (result as ActionResult.Success).result.metadata[META_GRAPH_ROLE_PREFIX + "sender"],
        )
    }

    @Test
    fun `an invented id changes nothing — no object, no metadata, no prose`() = runTest {
        val result = ClassifyRealizer(llmAnswering("sender=P99")).perform(document(), null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `«ничего не нашёл» is reported as such, not as a crash`() = runTest {
        val result = ClassifyRealizer(llmAnswering(CLASSIFIER_NOTHING)).perform(document(), null)

        assertTrue(result is ActionResult.Failure)
        assertEquals("Стороны в документе не найдены", (result as ActionResult.Failure).reason)
    }

    @Test
    fun `an empty document never reaches the model`() = runTest {
        val blank = PointObject(
            "x", "text/plain", ScratchRef(File(tmp.root, "empty.txt").apply { writeText("  \n\n") }.absolutePath),
            ObjectState(ObjectKind.TEXT),
        )
        val neverCalled = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) = error("LLM must not be called")
        }

        val result = ClassifyRealizer(neverCalled).perform(blank, null)

        assertEquals("Нет текста для разбора", (result as ActionResult.Failure).reason)
    }

    @Test
    fun `only the layout leaves the device — no ids, kinds or relations of ours`() = runTest {
        ClassifyRealizer(llmAnswering("sender=P2")).perform(document(), null)

        assertTrue(lastPrompt.contains("P2: ТОВ «Агротрейд»"))
        assertFalse("id объекта Point не должен покидать устройство", lastPrompt.contains("cmr"))
        assertFalse(lastPrompt.contains("Organization"))
    }

    // --- The capability's own promises ---

    @Test
    fun `it is a deliberate, paid, networked tap — never part of the first screen`() {
        val cap = ClassifyCapability()

        assertTrue(cap.meta.network)
        assertTrue(cap.meta.auth)
        assertEquals(com.point.core.flow.Cost.PAID, cap.meta.cost)
        assertEquals(com.point.core.flow.Latency.SLOW, cap.meta.latency)
    }

    @Test
    fun `it offers itself where there is text to read`() {
        val cap = ClassifyCapability()

        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_TEXT))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.COLLECTION)))
    }
}
