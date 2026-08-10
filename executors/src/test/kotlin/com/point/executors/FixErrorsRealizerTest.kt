package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Исправить ошибки» и «Исправить сильнее» (#666). Обе двери — знание того же объекта;
 * разница ровно одна: во вторую уходит и сам снимок.
 */
class FixErrorsRealizerTest {

    private var lastObject: PointObject? = null
    private var lastPrompt: String? = null

    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastObject = obj
            lastPrompt = prompt
            val f = File.createTempFile("fix-", ".txt").apply { deleteOnExit(); writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private val sender = META_GRAPH_ROLE_PREFIX + "sender"

    private fun photo(metadata: Map<String, String> = mapOf(sender to "Паринкн")) =
        PointObject("img", "image/jpeg", ScratchRef("/tmp/скан.jpg"), ObjectState(ObjectKind.IMAGE), metadata)

    private val ready = AiReadiness { true }

    @Test
    fun `опечатка исправлена, прежнее значение осталось следом`() = runTest {
        val result = FixErrorsRealizer(llm("1 = Паринкін")).perform(photo(), null)

        assertTrue("ожидалось знание, вышло: $result", result is ActionResult.Done)
        val done = result as ActionResult.Done
        assertEquals("Исправлено: 1", done.message)
        assertEquals("Паринкін", done.findings!!.metadata[sender])
        assertTrue("след прежнего значения потерян", done.findings!!.metadata.containsKey(sender + META_ALT_SUFFIX))
    }

    @Test
    fun `исправление — знание того же объекта, нового объекта не рождается`() = runTest {
        val done = FixErrorsRealizer(llm("1 = Паринкін")).perform(photo(), null) as ActionResult.Done

        assertTrue("понимание не смеет плодить объекты", done.findings!!.objects.isEmpty())
    }

    @Test
    fun `нечего править — это исход, а не отказ и не пустая правка`() = runTest {
        val result = FixErrorsRealizer(llm("NONE")).perform(photo(), null)

        val done = result as ActionResult.Done
        assertEquals("Ошибок не нашлось — знание оставлено как было", done.message)
        assertNull("пустая правка не должна ехать знанием", done.findings)
    }

    @Test
    fun `первая ступень снимок наружу не отправляет`() = runTest {
        FixErrorsRealizer(llm("NONE")).perform(photo(), null)

        assertFalse(
            "наружу ушёл снимок, хотя просили проверить только знание: ${lastObject!!.mime}",
            lastObject!!.mime.startsWith("image/"),
        )
    }

    @Test
    fun `«сильнее» отправляет сам снимок и говорит об этом модели`() = runTest {
        FixErrorsStrongerRealizer(llm("NONE")).perform(photo(), null)

        assertTrue("снимок обязан уйти на сверку", lastObject!!.mime.startsWith("image/"))
        assertTrue("модель не предупреждена, что источник приложен", "снимком" in lastPrompt!!)
    }

    @Test
    fun `подтверждённое человеком в модель не уходит`() = runTest {
        val confirmed = photo(
            mapOf(sender to "Паринкн", sender + com.point.core.flow.META_SOURCE_SUFFIX to Provenance.HUMAN.wire),
        )

        val result = FixErrorsRealizer(llm("1 = Паринкін")).perform(confirmed, null)

        assertTrue("исправлять было нечего — слово человека трогать нельзя", result is ActionResult.Failure)
        assertNull("до модели дело дойти не должно", lastPrompt)
    }

    @Test
    fun `дверь появляется только там, где есть что исправлять`() {
        val cap = FixErrorsCapability(ready)
        val withKnowledge = GraphState(photo())
        val bare = GraphState(photo(metadata = emptyMap()))

        assertTrue(cap.accepts(withKnowledge))
        assertFalse("на объекте без знания двери быть не должно", cap.accepts(bare))
    }

    @Test
    fun `«сильнее» предлагается только там, где есть источник для сверки`() {
        val cap = FixErrorsStrongerCapability(ready)
        val text = PointObject(
            "t", "text/plain", ScratchRef("/tmp/t.txt"), ObjectState(ObjectKind.TEXT), mapOf(sender to "Паринкн"),
        )

        assertTrue(cap.accepts(GraphState(photo())))
        assertFalse("у текста сверять знание не с чем", cap.accepts(GraphState(text)))
    }

    @Test
    fun `платное, сетевое, требующее ключа — обе двери`() {
        listOf(FixErrorsCapability(ready).meta, FixErrorsStrongerCapability(ready).meta).forEach { meta ->
            assertTrue(meta.network)
            assertTrue(meta.auth)
            assertEquals(com.point.core.flow.Cost.PAID, meta.cost)
        }
    }
}
