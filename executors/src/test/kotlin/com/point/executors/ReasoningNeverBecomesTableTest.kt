package com.point.executors

import com.point.core.flow.CollectionContent
import com.point.core.flow.CropEvidence
import com.point.core.flow.EvidenceCropper
import com.point.core.flow.EvidenceImage
import com.point.core.flow.HttpJson
import com.point.core.flow.HttpResult
import com.point.core.flow.ObjectStore
import com.point.core.flow.OpenAiCompatibleClient
import com.point.core.flow.OpenAiProvider
import com.point.core.flow.SheetPlan
import com.point.core.flow.SpreadsheetWriter
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ход мысли модели вслух не доходит до человека и таблицей не становится (#1320).
 *
 * Живой случай владельца 26.08.2026: «В Excel» отдало лист, где строки 1–33 заняты
 * рассуждением думающей модели, а товары накладной видны только внутри этого рассуждения.
 *
 * Дорога здесь настоящая от начала до конца: ответ сервиса приходит тем же клиентом, каким
 * приходит в жизни, — очистка ответа живёт внутри него, одна на все действия, а не заплаткой
 * у «В Excel». Поэтому проверка и живёт в `:executors`: она собирает и цепочку, и действие.
 */
class ReasoningNeverBecomesTableTest {

    private var rows: List<List<String>>? = null

    private val writer = object : SpreadsheetWriter {
        override suspend fun write(
            rows: List<List<String>>,
            candidates: Map<Pair<Int, Int>, List<String>>,
        ): ScratchRef {
            this@ReasoningNeverBecomesTableTest.rows = rows
            return ScratchRef(File.createTempFile("point-xlsx", ".xlsx").apply { deleteOnExit() }.absolutePath)
        }

        override suspend fun write(plan: SheetPlan): ScratchRef = write(plan.rows, plan.candidates)
    }

    private val noCrops = object : EvidenceCropper {
        override suspend fun crop(evidence: CropEvidence): EvidenceImage? = null
    }

    private val scratch = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String): PointObject = error("unused")
        override suspend fun ingestMultiple(sources: List<String>): PointObject = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: CapabilityId?,
        ): PointObject = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) =
            CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int): String = ""
        override suspend fun newScratchFile(extension: String) = ScratchRef(
            File.createTempFile("point-ans", ".$extension").apply { deleteOnExit() }.absolutePath,
        )
        override suspend fun clear() = Unit
    }

    /** Думающая модель, отвечающая по-настоящему — через клиента, а не мимо него. */
    private fun thinkingService(content: String) = OpenAiCompatibleClient(
        object : HttpJson {
            override suspend fun post(url: String, headers: Map<String, String>, body: String) =
                HttpResult(
                    200,
                    JSONObject().put(
                        "choices",
                        JSONArray().put(
                            JSONObject().put("message", JSONObject().put("content", content)),
                        ),
                    ).toString(),
                )
        },
        scratch,
        OpenAiProvider("сервис", "https://x/v1", "sk-key", "gpt-oss-20b"),
    )

    private val invoice = PointObject(
        "id",
        "text/plain",
        ScratchRef(
            File.createTempFile("point-doc", ".txt")
                .apply { deleteOnExit(); writeText("Гречка 2 шт 42") }
                .absolutePath,
        ),
        ObjectState(ObjectKind.TEXT),
    )

    private suspend fun toExcel(answer: String): ActionResult =
        ExcelRealizer(listOf(thinkingService(answer)), writer, noCrops, scratch, testKnowledge())
            .perform(invoice)

    @Test
    fun `рассуждение вслух в лист не едет — в таблицу попадает таблица`() = runTest {
        val table = "Товар\tЦіна\nГречка\t42"

        val result = toExcel(
            "<think>Okay, the user wants me to extract data from a document image.\n" +
                "**1. Analyze the Document Structure**\n</think>\n\n" + table,
        )

        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(listOf("Товар", "Ціна"), listOf("Гречка", "42")), rows)
    }

    @Test
    fun `проза вместо таблицы — отказ словами, а не файл с чужим текстом`() = runTest {
        val result = toExcel(
            "The user wants me to extract data from a document image.\n" +
                "**1. Analyze the Document Structure:**\n" +
                "The document appears to be an invoice with several line items.",
        )

        assertNull("проза уехала в файл человека", rows)
        val said = (result as ActionResult.Failure).reason
        assertTrue(said, said.contains(NOT_A_TABLE))
    }

    @Test
    fun `рассуждение, оборвавшееся на полуслове, не становится строками таблицы`() = runTest {
        val result = toExcel(
            "<think>Okay, the user wants me to extract data from a document image.\n" +
                "1. Гречка 2 шт\n2. Всього: 33 095,69",
        )

        assertTrue(result is ActionResult.Failure)
        assertNull("чужие мысли уехали в файл человека", rows)
    }
}
