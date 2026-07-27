package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** «Отклик» (#89): a job posting, once recognised, offers a ready reply — the user may
 *  add a line about themselves first (NeedsInput), like the free-form AI bubble. */
class JobReplyTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `exists only for a recognised job posting`() {
        val cap = JobReplyCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.IS_JOB))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.IS_RECIPE))))
        assertTrue(cap.meta.network)
    }

    @Test
    fun `first tap asks about the candidate, then the vacancy and the answer travel to the LLM`() = runTest {
        val vacancy = File(tmp.root, "v.txt").apply { writeText("Требуется Kotlin-разработчик, удалёнка") }
        val out = File(tmp.root, "reply.md").apply { writeText("Здравствуйте! Откликаюсь…") }
        var seenPrompt: String? = null
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                seenPrompt = prompt
                return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(out.absolutePath))
            }
        }
        val obj = PointObject(
            "id", "text/plain", ScratchRef(vacancy.absolutePath),
            ObjectState(ObjectKind.TEXT, setOf(Feature.IS_JOB)),
        )
        val realizer = JobReplyRealizer(llm)

        val ask = realizer.perform(obj, null)
        assertTrue(ask is ActionResult.NeedsInput)

        val result = realizer.perform(obj, "5 лет Android, Kotlin/Compose")
        assertTrue(result is ActionResult.Success)
        assertTrue(seenPrompt!!.contains("Kotlin-разработчик")) // the vacancy text travels
        assertTrue(seenPrompt!!.contains("5 лет Android"))      // and the user's line
        assertEquals("text/markdown", (result as ActionResult.Success).result.mime)
    }
}
