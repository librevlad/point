package com.point.bot

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val EXCEL_PROMPT =
    "Извлеки табличные данные из объекта (может быть фото рукописной таблицы под углом — читай " +
        "внимательно). Верни ТОЛЬКО JSON: массив строк, каждая строка — массив ячеек-строк, " +
        "например [[\"Имя\",\"Сумма\"],[\"Приказ\",\"42\"]]. Без пояснений."

/** Parse a JSON array-of-arrays (tolerating ```json fences and surrounding prose). */
fun parseTable(answer: String): List<List<String>> {
    val start = answer.indexOf('[')
    val end = answer.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    return runCatching {
        val arr = JSONArray(answer.substring(start, end + 1))
        (0 until arr.length()).map { r ->
            val row = arr.getJSONArray(r)
            (0 until row.length()).map { c -> row.optString(c) }
        }
    }.getOrDefault(emptyList())
}

/** Write rows to a minimal `.xlsx` by hand — ported from the app's OoxmlSpreadsheetWriter,
 *  inline strings, no shared-strings table, no Apache POI. */
fun writeXlsx(rows: List<List<String>>, target: File) {
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        put("[Content_Types].xml", CONTENT_TYPES)
        put("_rels/.rels", ROOT_RELS)
        put("xl/workbook.xml", WORKBOOK)
        put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        put("xl/worksheets/sheet1.xml", sheet(rows))
    }
}

private fun sheet(rows: List<List<String>>): String = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
    rows.forEachIndexed { r, cells ->
        append("""<row r="${r + 1}">""")
        cells.forEachIndexed { c, value ->
            append("""<c r="${col(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">""")
            append(xmlEscape(value))
            append("""</t></is></c>""")
        }
        append("</row>")
    }
    append("</sheetData></worksheet>")
}

private fun col(index: Int): String {
    var i = index
    val sb = StringBuilder()
    while (i >= 0) { sb.insert(0, 'A' + (i % 26)); i = i / 26 - 1 }
    return sb.toString()
}

private fun xmlEscape(s: String): String = buildString {
    for (ch in s) when {
        ch == '&' -> append("&amp;")
        ch == '<' -> append("&lt;")
        ch == '>' -> append("&gt;")
        ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
        else -> append(ch)
    }
}

private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Таблица" sheetId="1" r:id="rId1"/></sheets></workbook>"""
private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** «В Excel» for the bot (#92): photo/pdf/text → real xlsx. The standout bot demo —
 *  a photo of a handwritten table becomes a spreadsheet with no app in sight. */
class ExcelBotCapability : Capability {
    override val id = CapabilityId("excel")
    override val icon = "excel"
    override val meta = CapabilityMeta(priority = 16, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)
    override fun label(state: ObjectState) = "В Excel"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.PDF, ObjectKind.TEXT)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)
}

class ExcelBotRealizer(
    private val llm: LlmClient,
    private val scratchDir: File,
) : Realizer {
    override val capabilityId = CapabilityId("excel")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = withContext(Dispatchers.IO) {
        runCatching {
            val extra = if (input.state.kind == ObjectKind.TEXT) "\n\nТекст:\n" + File(input.uri.value).readText().take(20_000) else ""
            val answer = llm.run(input, EXCEL_PROMPT + extra)
            val rows = parseTable(File(answer.uri.value).readText())
            if (rows.isEmpty()) {
                ActionResult.Failure("Не удалось распознать таблицу", recoverable = true)
            } else {
                val out = File(scratchDir.apply { mkdirs() }, "table-${System.nanoTime()}.xlsx")
                writeXlsx(rows, out)
                ActionResult.Success(ResultObject(ObjectKind.OFFICE, XLSX_MIME, ScratchRef(out.absolutePath), mapOf("name" to "таблица.xlsx")))
            }
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось сделать таблицу", recoverable = true) }
    }
}
