package com.point.executors

import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
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

/** «Собрать данные+» (#128): AI twin of «Собрать данные» — richer categories (names,
 *  orgs, amounts, dates) via a strict line contract, grouped into a clean list. */
class CollectPlusTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `parses the strict category contract, dedupes and drops garbage`() {
        val answer = """
            NAME=Иван Петров
            ORG=ООО Ромашка
            AMOUNT=50 000 ₽
            DATE=в пятницу
            PHONE=+380671234567
            NAME=Иван Петров
            мусор без разделителя
            MOOD=хорошее
        """.trimIndent()
        val grouped = parseCollected(answer)
        assertEquals(listOf("Иван Петров"), grouped["NAME"])
        assertEquals(listOf("ООО Ромашка"), grouped["ORG"])
        assertEquals(listOf("50 000 ₽"), grouped["AMOUNT"])
        assertFalse(grouped.containsKey("MOOD")) // outside the whitelist
    }

    @Test
    fun `collects device and document identifiers - model, serial, code (real-device #128)`() {
        val grouped = parseCollected("MODEL=Apple Watch Series 11\nSERIAL=PH7QMF2D\nCODE=MEV44ZP/A")
        assertEquals(listOf("Apple Watch Series 11"), grouped["MODEL"])
        assertEquals(listOf("PH7QMF2D"), grouped["SERIAL"])
        assertEquals(listOf("MEV44ZP/A"), grouped["CODE"])
        val text = formatCollected(grouped)
        assertTrue(text.contains("Серийные номера:"))
        assertTrue(text.contains("PH7QMF2D"))
    }

    @Test
    fun `formats grouped categories into titled russian sections`() {
        val text = formatCollected(
            linkedMapOf("NAME" to listOf("Иван Петров"), "AMOUNT" to listOf("50 000 ₽")),
        )
        assertTrue(text.contains("Имена:"))
        assertTrue(text.contains("Иван Петров"))
        assertTrue(text.contains("Суммы:"))
    }

    @Test
    fun `accepts an entity-bearing object and is a paid network action`() {
        val cap = CollectPlusCapability()
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE))))
        assertFalse(cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertTrue(cap.meta.network)
        assertEquals("Собрать данные+", cap.label(ObjectState(ObjectKind.TEXT)))
    }

    @Test
    fun `runs the LLM over the text and materialises the grouped list`() = runTest {
        val src = File(tmp.root, "t.txt").apply { writeText("Иван из ООО Ромашка, бюджет 50000, дедлайн пятница") }
        val ans = File(tmp.root, "a.txt").apply { writeText("NAME=Иван\nORG=ООО Ромашка") }
        val outRef = ScratchRef(File(tmp.root, "out.txt").absolutePath)
        val store = object : ObjectStore {
            override suspend fun ingest(sourceUri: String, mime: String) = error("no")
            override suspend fun ingestMultiple(sources: List<String>) = error("no")
            override suspend fun put(result: ResultObject) = error("no")
            override suspend fun children(collection: PointObject) = emptyList<PointObject>()
            override suspend fun readText(obj: PointObject, limit: Int) = ""
            override suspend fun newScratchFile(extension: String) = outRef
            override suspend fun clear() {}
        }
        val llm = object : LlmClient {
            override suspend fun run(obj: PointObject, prompt: String): ResultObject {
                assertTrue(prompt.contains("Иван из ООО Ромашка"))
                return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(ans.absolutePath))
            }
        }
        val obj = PointObject("id", "text/plain", ScratchRef(src.absolutePath), ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE)))

        val result = CollectPlusRealizer(store, llm).perform(obj, null)

        assertTrue(result is ActionResult.Success)
        assertTrue(File(outRef.value).readText().contains("Иван"))
    }
}
