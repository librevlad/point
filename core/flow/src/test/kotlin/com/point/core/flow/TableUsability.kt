package com.point.core.flow

/**
 * Измеритель качества таблиц — не путь продукта, а инструмент прогона по корпусу (#1238).
 * Живёт в тестовом source set: его зовут только `TableScoreCli` и собственный тест, а задача
 * `scoreTable` и без того собрана на тестовом classpath. В боевом коде он читался как рабочий
 * путь и приглашал править себя вместо настоящего судьи — `unfitTable` в `TableFitness`.
 */
data class UsabilityScore(
    val frame: String,

    val sheetRows: Int,

    val dumpRows: Int,

    val documentCells: Int,

    val dumpCells: Int,

    val flaggedCells: Int,

    val noisyCells: Int,

    val bothCells: Int,
) {

    val cells: Int get() = documentCells + dumpCells

    val usableCells: Int get() = documentCells - (flaggedCells + noisyCells - bothCells)

    val usableShare: Double? get() = if (cells == 0) null else usableCells.toDouble() / cells

    val dumpShare: Double? get() = if (cells == 0) null else dumpCells.toDouble() / cells

    val noiseShare: Double? get() = if (documentCells == 0) null else noisyCells.toDouble() / documentCells

    val flaggedShare: Double? get() = if (documentCells == 0) null else flaggedCells.toDouble() / documentCells

    val unfit: List<Unfitness>
        get() = buildList {
            if (cells == 0) add(Unfitness.EMPTY)
            if ((dumpShare ?: 0.0) >= UNFIT_UNREAD_SHARE) add(Unfitness.DUMP)
            if ((noiseShare ?: 0.0) >= NOISE_SHARE) add(Unfitness.NOISE)
            if ((flaggedShare ?: 0.0) >= WARNING_WALL_SHARE) add(Unfitness.FLAGS)
        }
}

enum class Unfitness(val reason: String) {
    EMPTY("в файле нет ни одной непустой ячейки"),
    DUMP("непрочитанного больше трети листа — человек получил не таблицу, а её обломки"),
    NOISE("символьный шум в каждой десятой ячейке и чаще — значения приходится перенабирать"),

    FLAGS("предупреждение стоит на трети ячеек и больше — работать с таблицей нельзя, только перепроверять"),
}

/**
 * Порог шума — свой у измерителя, и продуктовой опоры у него нет (#1238): по символьному шуму
 * Point таблицу не отклоняет, отклоняет `unfitTable` — по непрочитанному и по разошедшимся
 * чтениям. Число здесь нужно, чтобы сравнивать прогоны корпуса между собой и видеть, что
 * шума стало больше или меньше, а не чтобы решать судьбу таблицы у человека. Доли
 * непрочитанного и меток берутся у настоящего судьи (`UNFIT_UNREAD_SHARE`,
 * `WARNING_WALL_SHARE`) — измеритель обязан мерить то, что получает человек.
 */
const val NOISE_SHARE: Double = 1.0 / 10.0

fun looksNoisy(cell: String): Boolean {
    val text = styleCell(cell).value.trim()
    if (text.isEmpty()) return false
    if (text.any { it in IMPOSSIBLE_CHARS }) return true
    if (!balanced(text, '(', ')') || !balanced(text, '[', ']')) return true
    if (!balanced(text, '«', '»') || !balanced(text, '“', '”')) return true
    if (text.count { it == '"' } % 2 != 0) return true
    if (text.last() in TRAILING_NOISE) return true
    return text.split(WORDS).any(::mixedScript)
}

fun scoreUsable(frame: String, sheet: List<List<String>>, unreadFrom: Int? = null): UsabilityScore {
    // Границу хвоста несёт план листа, а не служебная строка в нём (#1368).
    val document = if (unreadFrom == null) sheet else sheet.take(unreadFrom)
    val dump = if (unreadFrom == null) emptyList() else sheet.drop(unreadFrom)

    fun cellsOf(rows: List<List<String>>) = rows.flatten().filter { it.isNotBlank() }
    val documentCells = cellsOf(document)
    val dumpCells = cellsOf(dump)

    val flagged = documentCells.filter { styleCell(it).flagged }
    val noisy = documentCells.filter(::looksNoisy)

    return UsabilityScore(
        frame = frame,
        sheetRows = sheet.count { row -> row.any { it.isNotBlank() } },
        dumpRows = dump.count { row -> row.any { it.isNotBlank() } },
        documentCells = documentCells.size,
        dumpCells = dumpCells.size,
        flaggedCells = flagged.size,
        noisyCells = noisy.size,
        bothCells = documentCells.count { styleCell(it).flagged && looksNoisy(it) },
    )
}

private const val IMPOSSIBLE_CHARS = "_|\\^{}<>"

private const val TRAILING_NOISE = ",;!'"

private val WORDS = Regex("""[\s]+""")

private fun balanced(text: String, open: Char, close: Char): Boolean =
    text.count { it == open } == text.count { it == close }

private fun mixedScript(word: String): Boolean {
    var latin = false
    var cyrillic = false
    word.forEach { c ->
        if (!c.isLetter()) return@forEach
        when (Character.UnicodeBlock.of(c)) {
            Character.UnicodeBlock.BASIC_LATIN, Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
            Character.UnicodeBlock.LATIN_EXTENDED_A, Character.UnicodeBlock.LATIN_EXTENDED_B,
            -> latin = true
            Character.UnicodeBlock.CYRILLIC, Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> cyrillic = true
            else -> {}
        }
    }
    return latin && cyrillic
}
