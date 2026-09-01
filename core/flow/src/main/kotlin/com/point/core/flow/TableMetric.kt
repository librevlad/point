package com.point.core.flow

data class TableExpectation(

    val frame: String,

    val documentRows: Int,

    val documentColumns: Int,

    val keyColumn: Int,

    val header: Boolean,

    val namedRows: List<ExpectedRow>,
)

data class ExpectedRow(val key: String, val cells: Map<Int, String>)

data class CellDiff(
    val key: String,

    val column: Int,
    val expected: String,
    val actual: String?,
)

enum class TableFailure(val reason: String) {

    SILENT_CELLS("значение неверное, и ничто об этом не предупредило"),
    LOST_ROWS("строка документа в файл не попала"),
    EXTRA_ROWS("в файле есть строки, которых в документе нет"),
    WARNING_WALL("предупреждение стоит на трети ячеек и больше — таблицу приходится перепроверять целиком"),
    WRONG_SHAPE("ширина таблицы не совпала с документом"),
}

const val WARNING_WALL_SHARE: Double = 1.0 / 3.0

data class TableScore(
    val frame: String,

    val documentRows: Int,

    val tableRows: Int,
    val documentColumns: Int,

    val tableColumns: Int,

    val found: List<String>,

    val lost: List<String>,

    val extra: Int,

    val matchedCells: Int,

    val flagged: List<CellDiff>,

    val silent: List<CellDiff>,

    val markedCells: Int,

    val totalCells: Int,
) {

    val checkedCells: Int get() = matchedCells + flagged.size + silent.size

    val cellShare: Double? get() = if (checkedCells == 0) null else matchedCells.toDouble() / checkedCells

    val markedShare: Double? get() = if (totalCells == 0) null else markedCells.toDouble() / totalCells

    val failures: List<TableFailure>
        get() = buildList {
            if (silent.isNotEmpty()) add(TableFailure.SILENT_CELLS)
            if (lost.isNotEmpty()) add(TableFailure.LOST_ROWS)
            if (extra > 0) add(TableFailure.EXTRA_ROWS)
            if ((markedShare ?: 0.0) >= WARNING_WALL_SHARE) add(TableFailure.WARNING_WALL)
            if (tableColumns != documentColumns) add(TableFailure.WRONG_SHAPE)
        }

    val passed: Boolean get() = failures.isEmpty() && checkedCells > 0

    val unjudged: Boolean get() = failures.isEmpty() && checkedCells == 0
}

fun scoreTable(expectation: TableExpectation, sheet: List<List<String>>): TableScore {
    val expectedByKey = expectation.namedRows.associateBy { foldValue(it.key) }
    val width = sheet.maxOfOrNull { it.size } ?: 0

    var keyColumn = expectation.keyColumn
    var bestHits = 0
    for (c in 0 until width) {
        val hits = sheet.mapNotNull { row -> row.getOrNull(c)?.let(::foldValue) }
            .filter { it.isNotEmpty() && it in expectedByKey }
            .distinct().size
        if (hits > bestHits) {
            bestHits = hits
            keyColumn = c
        }
    }

    fun keyOf(row: List<String>): String? =
        row.getOrNull(keyColumn)?.let(::foldValue)?.takeIf { it.isNotEmpty() }

    val table = tableRowsOf(sheet, expectedByKey.keys, ::keyOf)

    val firstKey = table.firstOrNull()?.let(::keyOf)
    val headerRows = if (expectation.header && (firstKey == null || firstKey !in expectedByKey)) 1 else 0
    val data = table.drop(headerRows)

    val rowByKey = LinkedHashMap<String, List<String>>()
    var unknownRows = 0
    data.forEach { row ->
        val key = keyOf(row)

        if (key != null && key in expectedByKey && key !in rowByKey) rowByKey[key] = row else unknownRows++
    }

    val found = expectation.namedRows.filter { foldValue(it.key) in rowByKey }.map { it.key }
    val lost = expectation.namedRows.filter { foldValue(it.key) !in rowByKey }.map { it.key }

    val unnamed = (expectation.documentRows - expectation.namedRows.size).coerceAtLeast(0)
    val extra = (unknownRows - unnamed).coerceAtLeast(0)

    val shift = keyColumn - expectation.keyColumn
    var matched = 0
    val flagged = mutableListOf<CellDiff>()
    val silent = mutableListOf<CellDiff>()
    expectation.namedRows.forEach { expected ->
        val row = rowByKey[foldValue(expected.key)]
        expected.cells.entries.sortedBy { it.key }.forEach { (column, want) ->
            val raw = row?.getOrNull(column + shift)
            val cell = raw?.let(::styleCell)
            when {
                cell != null && foldValue(cell.value) == foldValue(want) -> matched++
                cell != null && cell.flagged -> flagged += CellDiff(expected.key, column, want, cell.value)
                else -> silent += CellDiff(expected.key, column, want, cell?.value)
            }
        }
    }

    return TableScore(
        frame = expectation.frame,
        documentRows = expectation.documentRows,
        tableRows = data.size,
        documentColumns = expectation.documentColumns,
        tableColumns = data.maxOfOrNull { it.size } ?: 0,
        found = found,
        lost = lost,
        extra = extra,
        matchedCells = matched,
        flagged = flagged,
        silent = silent,
        markedCells = data.sumOf { row -> row.count { styleCell(it).flagged } },
        totalCells = data.sumOf { it.size },
    )
}

private fun tableRowsOf(
    sheet: List<List<String>>,
    keys: Set<String>,
    keyOf: (List<String>) -> String?,
): List<List<String>> {
    // Хвост непрочитанного больше не помечен служебной строкой (#1368): узкие строки
    // хвоста отсеивает мерка ширины — строка таблицы не бывает уже строк с ключами.
    val narrowest = sheet.filter { keyOf(it) in keys }.minOfOrNull { it.size } ?: return sheet
    return sheet.filter { it.size >= narrowest }
}

fun parseTableExpectation(frame: String, text: String): TableExpectation {
    var documentRows: Int? = null
    var documentColumns: Int? = null
    var keyColumn = 0
    var header = true
    val named = mutableListOf<ExpectedRow>()
    var inBody = false

    text.lineSequence().forEachIndexed { index, raw ->
        val line = raw.trimEnd()
        val no = index + 1
        if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
        if (line.trim() == BODY_SEPARATOR) {
            require(!inBody) { "строка $no — разделитель «$BODY_SEPARATOR» уже был" }
            inBody = true
            return@forEachIndexed
        }
        if (!inBody) {

            val parts = line.substringBefore('#').trim().split(DIRECTIVE_SPLIT, limit = 2)
            val value = parts.getOrElse(1) { "" }.trim()
            fun number(): Int = value.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("строка $no — «${parts[0]}» ждёт число, а не «$value»")
            when (parts[0]) {
                "строк" -> documentRows = number()
                "колонок" -> documentColumns = number()
                "ключ" -> keyColumn = number().also {
                    require(it >= 1) { "строка $no — «ключ» считается от 1, а не от 0" }
                } - 1
                "шапка" -> header = when (value) {
                    "да" -> true
                    "нет" -> false
                    else -> throw IllegalArgumentException("строка $no — «шапка» ждёт «да» или «нет»")
                }
                else -> throw IllegalArgumentException("строка $no — неизвестное поле «${parts[0]}»")
            }
        } else {
            val fields = line.split('\t')
            val key = fields.getOrElse(keyColumn) { "" }.trim()
            require(key.isNotEmpty()) { "строка $no — нет ключа в колонке ${keyColumn + 1}" }
            require(named.none { foldValue(it.key) == foldValue(key) }) { "строка $no — ключ «$key» уже назван" }
            named += ExpectedRow(
                key = key,
                cells = fields.withIndex()
                    .filter { it.index != keyColumn && it.value.isNotBlank() }
                    .associate { it.index to it.value.trim() },
            )
        }
    }

    val rows = requireNotNull(documentRows) { "нет поля «строк» — без него потерянные строки не видны" }
    val columns = requireNotNull(documentColumns) { "нет поля «колонок»" }
    require(columns >= 1) { "«колонок» не может быть меньше одной" }
    require(keyColumn < columns) { "колонка-ключ ${keyColumn + 1} за пределами $columns колонок" }
    require(named.size <= rows) { "названо строк (${named.size}) больше, чем заявлено в документе ($rows)" }
    named.forEach { row ->
        row.cells.keys.forEach { column ->
            require(column < columns) { "строка «${row.key}» — колонка ${column + 1} за пределами $columns" }
        }
    }
    return TableExpectation(frame, rows, columns, keyColumn, header, named)
}

private const val BODY_SEPARATOR = "--"
private val DIRECTIVE_SPLIT = Regex("""\s+""")
private val SPACES = Regex("""\s+""")

private val NBSP = Char(0xA0)

private fun foldValue(s: String): String =
    styleCell(s).value
        .replace(NBSP, ' ')
        .replace(',', '.')
        .replace(QUOTES, "")
        .replace(SPACES, "")
        .lowercase()
        .trim(*EDGE_NOISE)

private val QUOTES = Regex("""[«»„“”"'‘’]""")

private val EDGE_NOISE = charArrayOf('.', '(', ')')
