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
import org.json.JSONArray
import java.io.File
import javax.inject.Inject

/**
 * image / pdf / text -> a spreadsheet. The LLM does the hard part (understanding a
 * real-world table); we ask for a **structured JSON** array-of-arrays — far more
 * reliable than parsing delimited text — and materialise a real .xlsx. Paid/network,
 * so it is a Pro action off the first screen (gated by the paywall seam).
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
                // For image/pdf the file is inlined by the LLM client; for text we pass content in the prompt.
                val extra = if (input.state.kind == ObjectKind.TEXT) {
                    "\n\nТекст:\n" + File(input.uri.value).readText().take(MAX_TEXT)
                } else {
                    ""
                }
                val answer = llm.run(input, PROMPT + extra)
                val rows = parseTable(File(answer.uri.value).readText())
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

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MAX_TEXT = 20_000
        const val PROMPT =
            "Извлеки табличные данные из документа. Это может быть фото рукописной таблицы, " +
                "возможно под углом или повёрнутое — читай внимательно в любой ориентации. " +
                "Верни ТОЛЬКО JSON: массив строк, каждая строка — массив ячеек-строк, например " +
                "[[\"Имя\",\"Сумма\"],[\"Приказ\",\"42\"]]. " +
                "ВАЖНО: в каждой строке ровно столько столбцов, сколько их в источнике — не добавляй, " +
                "не повторяй и не дублируй столбцы. Не выдумывай данные: если ячейка нечитаема, поставь \"?\". " +
                "Первая строка — заголовки, если они есть. Без пояснений, без markdown, без ограждений ```."
    }
}

/**
 * Robust table parse: a structured JSON array-of-arrays first (what the model is now
 * asked for — reliable), falling back to TSV so a model that still answers in the old
 * delimited format keeps working. Tolerant of a stray code fence around either.
 */
internal fun parseTable(raw: String): List<List<String>> {
    val cleaned = raw.trim()
        .removePrefix("```json").removePrefix("```tsv").removePrefix("```")
        .removeSuffix("```")
        .trim()

    parseJsonTable(cleaned)?.let { return it }

    return cleaned.lineSequence()
        .filter { it.isNotBlank() }
        .map { line -> line.split('\t').map { it.trim() } }
        .toList()
}

/** A JSON array-of-arrays → rows, or null if it is not that shape (→ TSV fallback). */
private fun parseJsonTable(s: String): List<List<String>>? {
    if (!s.startsWith("[")) return null
    return runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            (arr.opt(i) as? JSONArray)?.let { row ->
                (0 until row.length()).map { j -> row.get(j).toString() }
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
