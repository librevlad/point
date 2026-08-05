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

/**
 * «Список покупок» (#87): the first action UNLOCKED by the semantic level — it exists
 * only for an object that IS a recipe, which only study (#89) can establish.
 */
class ShoppingListTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `exists only for a recipe — the semantic feature is the gate`() {
        val cap = ShoppingListCapability(aiKeysReady)
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.IS_RECIPE))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.IS_MEETING))))
        assertTrue(cap.meta.network)
    }

    @Test
    fun `sends the recipe text with the fixed prompt and returns the list`() = runTest {
        val recipe = File(tmp.root, "r.txt").apply { writeText("Борщ: свёкла 2 шт, капуста 300 г") }
        val out = File(tmp.root, "list.md").apply { writeText("- свёкла 2 шт\n- капуста 300 г") }
        var seenPrompt: String? = null
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                seenPrompt = prompt
                return ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(out.absolutePath))
            }
        }
        val obj = PointObject(
            "id", "text/plain", ScratchRef(recipe.absolutePath),
            ObjectState(ObjectKind.TEXT, setOf(Feature.IS_RECIPE)),
        )

        val result = ShoppingListRealizer(llm).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        assertTrue(seenPrompt!!.contains("список покупок", ignoreCase = true))
        assertTrue(seenPrompt!!.contains("свёкла")) // the recipe itself travels in the prompt
        assertEquals("text/markdown", (result as ActionResult.Success).result.mime)
    }

    @Test
    fun `сетевое ожидание названо своими словами (#288)`() = runTest {
        val recipe = File(tmp.root, "r2.txt").apply { writeText("Борщ") }
        val out = File(tmp.root, "list2.md").apply { writeText("- свёкла") }
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String) =
                ResultObject(ObjectKind.TEXT, "text/markdown", ScratchRef(out.absolutePath))
        }
        val obj = PointObject(
            "id", "text/plain", ScratchRef(recipe.absolutePath),
            ObjectState(ObjectKind.TEXT, setOf(Feature.IS_RECIPE)),
        )

        val heard = stagesHeard { ShoppingListRealizer(llm).perform(obj, null) }

        assertEquals(listOf("Модель собирает список"), heard)
    }
}
