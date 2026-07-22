package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.SpreadsheetWriter
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * image / pdf / text -> a spreadsheet. The LLM does the hard part (understanding a
 * real-world table); we ask for TSV — tabs and newlines are far more robust than CSV
 * quoting — and materialise a real .xlsx. Paid/network, so it is a Pro action off the
 * first screen (and gated by the paywall seam when entitlements say so).
 */
class ExcelCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "excel"
    override val meta = CapabilityMeta(cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "В Excel"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.PDF, ObjectKind.TEXT)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    companion object { val ID = CapabilityId("excel") }
}

class ExcelRealizer @Inject constructor(
    private val llm: LlmClient,
    private val writer: SpreadsheetWriter,
) : Realizer {
    override val capabilityId = ExcelCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // For image/pdf the file is inlined by the LLM client; for text we
                // pass the content in the prompt.
                val extra = if (input.state.kind == ObjectKind.TEXT) {
                    "\n\nТекст:\n" + File(input.uri.value).readText().take(MAX_TEXT)
                } else {
                    ""
                }
                val answer = llm.run(input, PROMPT + extra)
                val rows = parseTsv(File(answer.uri.value).readText())
                if (rows.isEmpty()) {
                    ActionResult.Failure("Не удалось распознать таблицу", recoverable = true)
                } else {
                    val ref = writer.write(rows)
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.OFFICE,
                            XLSX_MIME,
                            ref,
                            mapOf("op" to "excel", "name" to "таблица.xlsx", "rows" to rows.size.toString()),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка разбора в Excel", recoverable = true) }
        }

    /** Tab/newline split, tolerant of a stray ``` fence the model may wrap around it. */
    private fun parseTsv(raw: String): List<List<String>> = raw
        .trim()
        .removePrefix("```tsv").removePrefix("```").removeSuffix("```")
        .trim()
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { line -> line.split('\t').map { it.trim() } }
        .toList()

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MAX_TEXT = 20_000
        const val PROMPT =
            "Извлеки табличные данные из документа. Верни ТОЛЬКО таблицу в формате TSV: " +
                "строки разделены переносами строк, ячейки внутри строки — символом табуляции. " +
                "Без пояснений, без markdown, без ограждений ```. Первая строка — заголовки, если они есть."
    }
}
