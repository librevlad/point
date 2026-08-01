package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRealizerTest {

    private val failingLlm = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String) = error("LLM must not be called")
    }

    /** A resolver that fails if reached — for chat paths that must NOT route to a producer. */
    private val noResolver = object : dagger.Lazy<Resolver> {
        override fun get(): Resolver = error("resolver must not be used")
    }

    private fun lazyOf(resolver: Resolver) = object : dagger.Lazy<Resolver> {
        override fun get() = resolver
    }

    private fun ai(llm: LlmClient = failingLlm, resolver: dagger.Lazy<Resolver> = noResolver) =
        AiRealizer(llm, resolver)

    private val text = PointObject("id", "text/plain", ScratchRef("/tmp/x.txt"), ObjectState(ObjectKind.TEXT))
    private val image = PointObject("i", "image/png", ScratchRef("/tmp/x.png"), ObjectState(ObjectKind.IMAGE))

    @Test
    fun `first tap asks the user for input instead of calling the LLM`() = runTest {
        assertTrue(ai().perform(text, amendment = null) is ActionResult.NeedsInput)
    }

    @Test
    fun `the input request carries 3 context suggestions (#86)`() = runTest {
        val result = ai().perform(text, amendment = null) as ActionResult.NeedsInput
        assertEquals(aiSuggestions(ObjectKind.TEXT), result.suggestions)
        assertEquals(3, result.suggestions.size)
    }

    @Test
    fun `suggestions differ by object kind`() {
        assertNotEquals(aiSuggestions(ObjectKind.IMAGE), aiSuggestions(ObjectKind.PDF))
        assertTrue(aiSuggestions(ObjectKind.IMAGE).isNotEmpty())
    }

    @Test
    fun `a produce-word request delegates to the producer and returns its OBJECT, not the chat LLM`() = runTest {
        val docx = ResultObject(ObjectKind.OFFICE, "docx", ScratchRef("/tmp/out.docx"), mapOf("op" to "to-word-plus"))
        var delegatedTo: CapabilityId? = null
        val resolver = object : Resolver {
            override fun realizerFor(id: CapabilityId): Realizer {
                delegatedTo = id
                return object : Realizer {
                    override val capabilityId = id
                    override suspend fun perform(input: PointObject, amendment: String?) = ActionResult.Success(docx)
                }
            }
        }
        // failingLlm proves the chat LLM is never touched for a "produce word" request.
        val result = ai(resolver = lazyOf(resolver)).perform(text, amendment = "сделай ворд")
        assertEquals(WordPlusCapability.ID, delegatedTo)
        assertEquals(docx, (result as ActionResult.Success).result)
    }

    @Test
    fun `a question stays a chat answer from the LLM and never routes`() = runTest {
        val answer = ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/tmp/a.md"))
        val chatLlm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) = answer
        }
        // IMAGE so buildPrompt reads no file; noResolver errors if the router is (wrongly) hit.
        val result = ai(llm = chatLlm).perform(image, amendment = "что тут?")
        assertEquals(answer, (result as ActionResult.Success).result)
    }

    // --- #288: стадия там, где ждём мы, и молчание там, где работает делегат ---

    @Test
    fun `вопрос к модели называет себя`() = runTest {
        val chatLlm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) =
                ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef("/tmp/a.md"))
        }

        val heard = stagesHeard { ai(llm = chatLlm).perform(image, amendment = "что тут?") }

        assertEquals(listOf("Спрашиваю модель"), heard)
    }

    @Test
    fun `запрос формата уходит производителю — и рассказывает о себе уже он, а не AI`() = runTest {
        val docx = ResultObject(ObjectKind.OFFICE, "docx", ScratchRef("/tmp/out.docx"))
        val resolver = object : Resolver {
            override fun realizerFor(id: CapabilityId) = object : Realizer {
                override val capabilityId = id
                override suspend fun perform(input: PointObject, amendment: String?) = ActionResult.Success(docx)
            }
        }

        val heard = stagesHeard { ai(resolver = lazyOf(resolver)).perform(text, amendment = "сделай ворд") }

        assertTrue("AI своих слов о чужой работе не выдумывает", heard.isEmpty())
    }

    @Test
    fun `первый тап только спрашивает человека — ждать нечего, стадии нет`() = runTest {
        assertTrue(stagesHeard { ai().perform(text, amendment = null) }.isEmpty())
    }
}
