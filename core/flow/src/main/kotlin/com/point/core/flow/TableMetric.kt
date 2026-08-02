package com.point.core.flow

/**
 * Эталон таблицы одного кадра корпуса — то, что человек размечает **один раз**, глядя на фото (#262).
 *
 * Семь кадров из 22 — это «извлечь таблицу», и до сих пор они не мерились ничем. Схему готовности
 * ([ActionSchema]) им завести нельзя: честного факта «в документе есть таблица» не существует, и
 * такая схема накрутила бы метрику пустой готовностью. Поэтому таблица меряется **по результату
 * действия** — по тому самому `.xlsx`, который человек открыл, — а эталон отвечает ровно на то, что
 * человек и так видит на фото: сколько строк, сколько колонок и несколько значений, которые он
 * сверил глазами. Пять минут на кадр, а не построчная расшифровка.
 *
 * Разметка **неполна по построению**, и это не изъян. На эталонной ведомости владельца (кадр 23:
 * печатный бланк воинской части, ~35 строк × 8 колонок, поверх печати — синяя ручка) названы 27
 * строк — те, у которых в первой колонке есть артикул (`11004`, `11006`, `11012`…). Строки без
 * артикула назвать нечем, и они живут только в числе [documentRows]. Метрика обязана различать
 * «строку не назвали» и «строки нет» — иначе она объявит потерей всё, что человек поленился
 * переписать.
 *
 * Формат файла эталона — [parseTableExpectation]. Заготовка кадра 23 — `tools/corpus/23.expected.tsv`.
 */
data class TableExpectation(
    /** Имя кадра корпуса — то же, что у файла: «23». */
    val frame: String,
    /** Сколько в документе строк ДАННЫХ: строка шапки сюда не входит. */
    val documentRows: Int,
    /** Сколько в документе колонок. */
    val documentColumns: Int,
    /** Колонка-ключ в координатах эталона, 0-based. На ведомости это артикул. */
    val keyColumn: Int,
    /** Есть ли у таблицы строка шапки — по ней метрика не судит, но в счёт строк её не берёт. */
    val header: Boolean,
    /** Названные строки: ключ плюс те ячейки, которые человек сверил глазами. */
    val namedRows: List<ExpectedRow>,
)

/**
 * Одна названная строка эталона. [cells] — только сверенное: номер колонки (0-based, координаты
 * эталона) → значение. Колонки-ключа среди них нет — по ключу строка опознаётся, и сверять его с
 * самим собой значило бы считать совпадением факт совпадения.
 */
data class ExpectedRow(val key: String, val cells: Map<Int, String>)

/** Одно расхождение с эталоном. [actual] `null` — ячейки в файле нет вовсе (строка потеряна). */
data class CellDiff(
    val key: String,
    /** Колонка в координатах эталона, 0-based. */
    val column: Int,
    val expected: String,
    val actual: String?,
)

/**
 * Чем именно провалена таблица. Список, а не флаг: «не сошлось» без имени причины — то же
 * молчание, от которого метрика и лечит.
 */
enum class TableFailure(val reason: String) {
    /** Главное число метрики: значение не то, и ничто в файле об этом не говорит. */
    SILENT_CELLS("значение неверное, и ничто об этом не предупредило"),
    LOST_ROWS("строка документа в файл не попала"),
    EXTRA_ROWS("в файле есть строки, которых в документе нет"),
    WARNING_WALL("предупреждение стоит на трети ячеек и больше — таблицу приходится перепроверять целиком"),
    WRONG_SHAPE("ширина таблицы не совпала с документом"),
}

/**
 * Стена предупреждений: доля помеченных ⚠ ячеек, выше которой файл перестаёт быть результатом.
 *
 * Число не с потолка. Живой прогон «В Excel» на кадре 23 отдал 54 строки вместо ~35 и пометил
 * спорными 387 ячеек из ~430 — 90%. Формально там ни одного молчаливого расхождения: система
 * честно сказала «не уверен» почти обо всём. По существу человек получил не таблицу, а задание
 * перепроверить 430 ячеек вручную, то есть действие не выполнено. Порог — суждение, а не измерение,
 * и он назван вслух именно поэтому.
 */
const val WARNING_WALL_SHARE: Double = 1.0 / 3.0

/**
 * Итог сравнения выгруженной таблицы с эталоном кадра (#262).
 *
 * Числа разложены по смыслу, а не свёрнуты в одну оценку: «сколько строк нашлось» и «сколько
 * расхождений прошло молча» — разные обещания человеку, и среднее между ними ничего не значит.
 */
data class TableScore(
    val frame: String,
    /** Строк данных в документе — по эталону. */
    val documentRows: Int,
    /** Строк данных в файле — без строки шапки, если эталон её заявил. */
    val tableRows: Int,
    val documentColumns: Int,
    /** Ширина файла — по самой широкой строке данных. */
    val tableColumns: Int,
    /** Ключи названных строк, найденные в файле. */
    val found: List<String>,
    /** Ключи названных строк, которых в файле нет. */
    val lost: List<String>,
    /**
     * Строк в файле больше, чем документ мог дать. Считается не как «файл длиннее документа», а
     * как «неопознанных строк больше, чем эталон мог не назвать»: так ловится и подмена — три
     * строки потеряны, три придуманы, длина та же.
     */
    val extra: Int,
    /** Сверенных ячеек, совпавших с эталоном. */
    val matchedCells: Int,
    /** Расхождения, помеченные ⚠: цена проверки, а не ложь. */
    val flagged: List<CellDiff>,
    /** Расхождения, прошедшие молча. **Главное число метрики.** */
    val silent: List<CellDiff>,
    /** Помеченных ⚠ ячеек во всём файле. */
    val markedCells: Int,
    /** Всего ячеек в строках данных файла — знаменатель [markedShare]. */
    val totalCells: Int,
) {
    /** Сколько ячеек человек сверил глазами — знаменатель [cellShare]. */
    val checkedCells: Int get() = matchedCells + flagged.size + silent.size

    /** Доля сверенных ячеек, совпавших с эталоном; `null` — сверять было нечего. */
    val cellShare: Double? get() = if (checkedCells == 0) null else matchedCells.toDouble() / checkedCells

    /** Доля ячеек файла, помеченных ⚠; `null` — файла нет вовсе. */
    val markedShare: Double? get() = if (totalCells == 0) null else markedCells.toDouble() / totalCells

    /** Чем провалено — в порядке важности. Пустой список = ни одной названной причины. */
    val failures: List<TableFailure>
        get() = buildList {
            if (silent.isNotEmpty()) add(TableFailure.SILENT_CELLS)
            if (lost.isNotEmpty()) add(TableFailure.LOST_ROWS)
            if (extra > 0) add(TableFailure.EXTRA_ROWS)
            if ((markedShare ?: 0.0) >= WARNING_WALL_SHARE) add(TableFailure.WARNING_WALL)
            if (tableColumns != documentColumns) add(TableFailure.WRONG_SHAPE)
        }

    /**
     * Сдано: ни одной названной причины провала — **и было чем судить**, то есть человек сверил
     * глазами хотя бы одну ячейку.
     *
     * Второе условие — не придирка, а дыра, найденная на живой заготовке. `tools/corpus/23.expected.tsv`
     * называет 27 артикулов и **ни одного** значения: файл, в котором артикулы прочитаны, ширина
     * сошлась, а все остальные ячейки пусты или выдуманы, не даёт ни потерянных строк, ни молчаливых
     * расхождений. Без этой оговорки метрика объявила бы такой файл «сданным» — статус, выданный за
     * отсутствие проверки, и есть та самая красивая видимость вместо факта.
     */
    val passed: Boolean get() = failures.isEmpty() && checkedCells > 0

    /**
     * Судить было не по чему: причин провала нет, но и сверенных ячеек нет — сошлись только строки
     * и ширина. Это не «сдано» и не «провал», а честное «замер неполон»; отдельное слово нужно
     * ровно затем, чтобы неполный замер не прятался ни за одним из двух.
     */
    val unjudged: Boolean get() = failures.isEmpty() && checkedCells == 0
}

/**
 * Сравнивает выгруженную таблицу [table] (дословные тексты ячеек, как их написал писатель `.xlsx`
 * — вместе с `⚠` и `~~было~~ стало`) с эталоном кадра. Чистая функция: ни файлов, ни сети, ни
 * Android — устройство и zip остаются в харнессе (`tools/table-score.sh`).
 *
 * Как строки узнают друг друга: по **колонке-ключу**, а не по номеру строки. Колонка-ключ файла
 * ищется как та, где нашлось больше всего эталонных ключей, — на живой ведомости одно чтение
 * отдаёт артикул первой колонкой, другое второй (первую заняла пустая колонка бланка), и счёт по
 * номеру строки мерил бы съехавшую сетку, а не чтение. Тем же сдвигом переносятся и номера колонок
 * сверяемых ячеек: лишняя колонка слева не должна объявлять расхождением всю таблицу.
 *
 * Судьба каждой сверенной ячейки — одна из трёх, и порядок здесь и есть смысл метрики:
 * - значение совпало (после свёртки формата) — **совпало**, помечено оно ⚠ или нет;
 * - не совпало, но ячейка помечена ⚠ — **честная неуверенность**: человек предупреждён;
 * - не совпало и не помечено (или ячейки нет вовсе, потому что строка потеряна) — **молча**.
 *
 * Ячейки потерянной строки считаются молчаливыми расхождениями наравне с ячейками испорченными:
 * для человека это одно и то же — значения нет, и ничто не сказало, что его нет. Строка при этом
 * ещё раз названа в [TableScore.lost]; единицы разные (строки и ячейки), двойного счёта нет.
 */
fun scoreTable(expectation: TableExpectation, table: List<List<String>>): TableScore {
    val expectedByKey = expectation.namedRows.associateBy { foldValue(it.key) }
    val width = table.maxOfOrNull { it.size } ?: 0

    // Колонка-ключ файла: где нашлось больше всего эталонных ключей. Ничего не нашлось — берём
    // колонку эталона: тогда всё честно потеряется, вместо того чтобы совпасть случайной колонкой.
    var keyColumn = expectation.keyColumn
    var bestHits = 0
    for (c in 0 until width) {
        val hits = table.mapNotNull { row -> row.getOrNull(c)?.let(::foldValue) }
            .filter { it.isNotEmpty() && it in expectedByKey }
            .distinct().size
        if (hits > bestHits) {
            bestHits = hits
            keyColumn = c
        }
    }

    fun keyOf(row: List<String>): String? =
        row.getOrNull(keyColumn)?.let(::foldValue)?.takeIf { it.isNotEmpty() }

    // Шапку эталон объявляет, но метрика не отдаёт ей первую строку вслепую: модель, потерявшая
    // шапку, отдаёт данными первую же строку, и слепое отбрасывание съело бы настоящую строку
    // документа. Первая строка с эталонным ключом — данные, чем бы её ни объявили.
    val firstKey = table.firstOrNull()?.let(::keyOf)
    val headerRows = if (expectation.header && (firstKey == null || firstKey !in expectedByKey)) 1 else 0
    val data = table.drop(headerRows)

    val rowByKey = LinkedHashMap<String, List<String>>()
    var unknownRows = 0
    data.forEach { row ->
        val key = keyOf(row)
        // Второй раз тот же ключ — не та же строка, а лишняя: строка документа одна.
        if (key != null && key in expectedByKey && key !in rowByKey) rowByKey[key] = row else unknownRows++
    }

    val found = expectation.namedRows.filter { foldValue(it.key) in rowByKey }.map { it.key }
    val lost = expectation.namedRows.filter { foldValue(it.key) !in rowByKey }.map { it.key }
    // Сколько строк документа эталон назвать не мог — столько неопознанных строк законны.
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

/**
 * Читает файл эталона. Формат — самый скупой из тех, что можно заполнить за пять минут, глядя на
 * фото:
 *
 * ```
 * # комментарий
 * строк 35            # столько же можно сказать в хвосте строки заголовка
 * колонок 8
 * ключ 1
 * шапка да
 * --
 * 11004	Гречка		120
 * 11006
 * ```
 *
 * До `--` — заголовок: `строк` и `колонок` обязательны, `ключ` (номер колонки-ключа, 1-based) по
 * умолчанию 1, `шапка да|нет` по умолчанию «да». После `--` — названные строки, TSV в координатах
 * документа: поле в колонке-ключе — ключ строки, остальные непустые поля — сверенные значения,
 * пустые поля — «не сверял». Строк можно назвать сколько угодно (в идеале все, у которых есть
 * ключ) — метрика знает, сколько документ мог дать сверх названного.
 *
 * `строк` и `колонок` не имеют значений по умолчанию сознательно. Взять их из числа названных
 * строк было бы удобно и было бы ложью: эталон, назвавший 27 строк из 35, объявил бы документ
 * 27-строчным, и восемь потерянных строк исчезли бы из метрики вместе с провалом.
 *
 * Всё, что нарушено, — [IllegalArgumentException] с номером строки. Молча пропущенная строка
 * эталона — это тихо уменьшенный знаменатель, то есть подделка числа в свою пользу.
 */
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
            // Хвостовой комментарий разрешён только здесь: в теле «#» — законный знак значения
            // («№», «#3»), и вырезать его там значило бы молча портить эталон.
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

/** Неразрывный пробел: `\s` в Java-регулярке его не знает, а в цифрах документа он встречается. */
private val NBSP = Char(0xA0)

/**
 * Свёртка значения для сверки с эталоном — **строже**, чем [normConsensus], которым голосуют
 * чтения. Голосованию можно складывать точки и запятые как формат-шум, метрике нельзя: тогда
 * «1.5» совпало бы с «15», и число польстило бы себе ровно там, где мерит правду.
 *
 * Складывается только то, что человек считает одним значением: маркеры `⚠`/`~~…~~` (их снимает
 * [styleCell]), регистр, любые пробелы (включая разрядный в «1 200»), десятичная запятая против
 * точки и **стиль кавычки**. Хвостовая точка снимается — «120.» и «120» человек читает одинаково.
 *
 * Кавычки складываются по замеру кадра 23: в бланке напечатано `Пластівці вівсяні “Екстра”`
 * типографскими лапками, модель отвечает прямыми — и метрика объявляла это молчаливым
 * расхождением, то есть винила чтение за оформление. Слово внутри кавычек при этом остаётся
 * различием: складывается сама кавычка, а не то, что она обрамляет.
 */
private fun foldValue(s: String): String =
    styleCell(s).value
        .replace(NBSP, ' ')
        .replace(',', '.')
        .replace(QUOTES, "")
        .replace(SPACES, "")
        .lowercase()
        .trimEnd('.')

/** Парные и прямые кавычки всех начертаний — оформление, а не значение. */
private val QUOTES = Regex("""[«»„“”"'‘’]""")
