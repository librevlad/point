package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.flow.SpreadsheetWriter
import com.point.core.flow.reconcile
import com.point.core.flow.styleCell
import com.point.core.flow.validateTable
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
    private val providers: List<@JvmSuppressWildcards LlmClient>,
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
                // #200: read the table with up to CONSENSUS_N independent strong-vision models, then
                // vote each cell (reconcile). A dense/handwritten table one model guesses, another catches
                // — agreement = confidence, disagreement = ⚠ + the models' distinct readings as candidates.
                val ordered = providers.sortedByDescending { it.strongVision }.filter { it.canHandle(input) }
                val tables = mutableListOf<List<List<String>>>()
                val errors = mutableListOf<String>()
                for (provider in ordered) {
                    if (tables.size >= CONSENSUS_N) break
                    try {
                        val answer = provider.run(input, PROMPT + extra)
                        parseTable(File(answer.uri.value).readText()).takeIf { it.isNotEmpty() }?.let(tables::add)
                    } catch (e: Exception) {
                        errors += e.message ?: e.javaClass.simpleName
                    }
                }
                if (tables.isEmpty()) {
                    ActionResult.Failure(
                        errors.firstOrNull()?.substringBefore('\n')?.take(120) ?: "Не удалось распознать таблицу",
                        recoverable = true,
                    )
                } else {
                    val consensus = reconcile(tables) // 1 read → passthrough; ≥2 → voted, disagreements ⚠
                    // model-free logic check also marks cells one would silently guess (letter-in-number,
                    // broken id run) with ⚠ so the writer highlights them.
                    val suspect = validateTable(consensus.rows)
                    val rows = consensus.rows.mapIndexed { r, row ->
                        row.mapIndexed { c, v -> if ((r to c) in suspect && !v.contains('⚠')) "$v⚠" else v }
                    }
                    val ref = writer.write(rows)
                    val flagged = rows.sumOf { row -> row.count { styleCell(it).flagged } }
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.OFFICE,
                            XLSX_MIME,
                            ref,
                            mapOf(
                                "op" to "excel", "name" to "таблица.xlsx",
                                "rows" to rows.size.toString(), "flagged" to flagged.toString(),
                                "models" to tables.size.toString(),
                            ),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка разбора в Excel", recoverable = true) }
        }

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val MAX_TEXT = 20_000

        /** Independent model reads to vote across (#200). 2 = confidence at 2× cost/latency, which a
         *  PAID/SLOW action can bear; a single-model setup degrades gracefully to a passthrough. */
        const val CONSENSUS_N = 2
        const val PROMPT =
            "Извлеки табличные данные из документа. Это может быть фото рукописной таблицы, " +
                "возможно под углом или повёрнутое — читай внимательно в любой ориентации. " +
                "Верни ТОЛЬКО JSON: массив строк, каждая строка — массив ячеек-строк, например " +
                "[[\"Дата\",\"Сумма\"],[\"16.07\",\"42\"]]. " +
                "Первая строка — заголовки, если они есть. " +
                "ВАЖНО: в каждой строке ровно столько столбцов, сколько их в источнике — не добавляй, " +
                "не повторяй и не дублируй столбцы. " +
                "Зачёркнутое/исправленное помечай так: \"~~53~~ 40\" (было 53, стало 40) или \"~~52~~\" " +
                "(просто зачёркнуто). " +
                "Если ячейку видно, но ты НЕ уверен в прочтении — добавь символ ⚠ в конец её текста " +
                "(например \"Гречка⚠\"): её подсветят для проверки. " +
                "Не выдумывай данные: если ячейку не разобрать совсем — оставь её пустой (\"\"), НЕ ставь " +
                "\"?\"; если не удаётся прочитать всю строку — пропусти её целиком. " +
                "Без пояснений, без markdown, без ограждений ```."
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
