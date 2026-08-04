package com.point.core.flow

/**
 * Годность файла: **сколько ячеек человек может взять как есть** (#493).
 *
 * Метрика таблиц ([scoreTable]) отвечает на вопрос «не соврал ли файл»: сколько строк нашлось,
 * сколько расхождений прошло молча. Это про правду — и владелец, получив 04.08.2026 разбор
 * ведомости, сказал про файл другое слово: «бедовая». Файл при этом мог не соврать ни разу:
 * половина листа была подписана «Непрочитанное», а в ячейках стояло `[6`, `_8.`, `А4152_`,
 * `солдат'`. Ни одно из этих слов не расходится с эталоном — их просто **нечем пользоваться**,
 * и старое число этого не видело вовсе.
 *
 * Поэтому вторая величина, отдельная от первой: не «правда ли то, что написано», а «можно ли
 * это открыть и работать». Складывать их в одну оценку нельзя — это два разных обещания человеку.
 *
 * Считается по тому же самому файлу, который человек открыл, без эталона: годность видно из
 * самого листа. Поэтому она меряется и на кадрах, у которых размеченного эталона нет.
 */
data class UsabilityScore(
    val frame: String,
    /** Непустых строк на листе — вместе с непрочитанным. */
    val sheetRows: Int,
    /** Непустых строк ниже подписи «Непрочитанное» (сама подпись не в счёте). */
    val dumpRows: Int,
    /** Непустых ячеек документа: всё, что выше подписи. */
    val documentCells: Int,
    /** Непустых ячеек в непрочитанном (подпись не в счёте). */
    val dumpCells: Int,
    /** Ячеек документа, помеченных ⚠. */
    val flaggedCells: Int,
    /** Ячеек документа с символьным шумом ([looksNoisy]). */
    val noisyCells: Int,
    /**
     * Ячеек документа, которые и помечены ⚠, и шумны. Нужны, чтобы «годные» считались вычитанием
     * объединения, а не суммы: иначе одна и та же ячейка вычиталась бы дважды и число годных
     * ушло бы вниз само по себе.
     */
    val bothCells: Int,
) {
    /** Всего непустых ячеек в файле — знаменатель [usableShare]: человек получил лист целиком. */
    val cells: Int get() = documentCells + dumpCells

    /**
     * Ячеек, которые можно взять как есть: ячейка документа, не помеченная ⚠ и без символьного
     * шума. Ячейки непрочитанного сюда не входят по определению — это слова без места в таблице.
     */
    val usableCells: Int get() = documentCells - (flaggedCells + noisyCells - bothCells)

    /** **Главное число:** доля листа, которую можно взять как есть; `null` — файл пуст. */
    val usableShare: Double? get() = if (cells == 0) null else usableCells.toDouble() / cells

    /** Доля листа, уехавшая в непрочитанное; `null` — файл пуст. */
    val dumpShare: Double? get() = if (cells == 0) null else dumpCells.toDouble() / cells

    /** Доля ячеек документа с символьным шумом; `null` — документа в файле нет вовсе. */
    val noiseShare: Double? get() = if (documentCells == 0) null else noisyCells.toDouble() / documentCells

    /** Доля ячеек документа, помеченных ⚠; `null` — документа в файле нет вовсе. */
    val flaggedShare: Double? get() = if (documentCells == 0) null else flaggedCells.toDouble() / documentCells

    /** Чем именно файл негоден — в порядке важности. Пустой список ≠ «хороший файл», см. [usableShare]. */
    val unfit: List<Unfitness>
        get() = buildList {
            if (cells == 0) add(Unfitness.EMPTY)
            if ((dumpShare ?: 0.0) >= DUMP_SHARE) add(Unfitness.DUMP)
            if ((noiseShare ?: 0.0) >= NOISE_SHARE) add(Unfitness.NOISE)
            if ((flaggedShare ?: 0.0) >= WARNING_WALL_SHARE) add(Unfitness.FLAGS)
        }
}

/** Чем файл негоден. Причина названа словом — «плохой файл» без имени причины чинить нечем. */
enum class Unfitness(val reason: String) {
    EMPTY("в файле нет ни одной непустой ячейки"),
    DUMP("непрочитанного больше четверти листа — человек получил не таблицу, а её обломки"),
    NOISE("символьный шум в каждой десятой ячейке и чаще — значения приходится перенабирать"),

    /**
     * Тот же порог, что у [TableFailure.WARNING_WALL], и то же по существу: файл, где помечена
     * треть ячеек, честен — и всё-таки не выполнен, потому что человек получил задание проверить
     * таблицу, а не таблицу. Названо здесь **своими словами и своим знаменателем**: правда и
     * годность — разные обещания, и человек, читающий про годность, не обязан идти за причиной
     * в соседнее число. Порог один на оба, чтобы два числа не разошлись молча.
     */
    FLAGS("предупреждение стоит на трети ячеек и больше — работать с таблицей нельзя, только перепроверять"),
}

/**
 * Порог свалки: доля листа в «непрочитанном», выше которой файл перестаёт быть таблицей.
 *
 * Число — **суждение, а не измерение**, и названо вслух именно поэтому (как [WARNING_WALL_SHARE]).
 * Опора: файл владельца от 04.08.2026 — 23 строки, из них 11 ниже подписи «Непрочитанное»;
 * это тот самый файл, про который сказано «бедовая».
 */
const val DUMP_SHARE: Double = 1.0 / 4.0

/**
 * Порог шума: доля ячеек документа с символьным шумом, выше которой файл перестаёт быть
 * результатом. Тоже суждение: одна испорченная ячейка из тридцати — досадно, но файл живой;
 * каждая десятая — человек перенабирает таблицу с фотографии, то есть действие не выполнено.
 */
const val NOISE_SHARE: Double = 1.0 / 10.0

/**
 * Символьный шум в ячейке: следы движка, а не текст документа.
 *
 * Правило намеренно **не** судит по «странным буквам»: `Квитанщя` вместо `Квитанція` — это
 * ошибка чтения, и ловит её эталон, а не эта проверка. Здесь ловится другое — то, чего в
 * набранном человеком значении не бывает **структурно**, чем бы документ ни был:
 *
 * - знак, который в документ не попадает вовсе: `_ | \ ^ { } < >` («`_8.`», «`[Mii 1 i`»);
 * - непарная скобка или кавычка («`[6`», «`(31.07.2026`»);
 * - запятая, восклицательный знак, апостроф или точка с запятой **в конце** значения
 *   («`7,`», «`солдат'`», «`Васильович!`»). Точка не в счёте — ею кончаются сокращения
 *   («`кв.м.`») и номера по порядку («`1.`»);
 * - латиница и кириллица **внутри одного слова** — след того, что движок выбирал букву по
 *   начертанию, а не по языку.
 *
 * Ложная тревога у правила измерена, а не обещана: `TableUsabilityTest` прогоняет его по всем
 * значениям, которые человек **сам** переписал в эталоны корпуса (каталог `tools/corpus`),
 * и держит число ложных срабатываний на нуле. Эталон — единственный доступный образец «текста,
 * набранного человеком по этому самому документу», и правило, кричащее на него, кричало бы и на
 * хорошо прочитанный файл.
 */
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

/**
 * Считает годность по листу [sheet] — дословным ячейкам файла, как их достал харнесс
 * (`tools/table-score.sh`, ⚠ уже возвращён из заливки в текст).
 *
 * Граница документа и непрочитанного берётся из самого файла: подпись [UNREAD_CAPTION] файл
 * печатает вслух, и всё от неё и ниже — слова без места в таблице (та же граница, что у
 * [scoreTable]). Подпись в счёт ячеек не идёт: она не данные, а честное объяснение.
 */
fun scoreUsable(frame: String, sheet: List<List<String>>): UsabilityScore {
    val caption = sheet.indexOfFirst { row ->
        row.firstOrNull()?.trimStart()?.startsWith(UNREAD_CAPTION) == true
    }
    val document = if (caption < 0) sheet else sheet.take(caption)
    val dump = if (caption < 0) emptyList() else sheet.drop(caption + 1)

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

/** Знаки, которые в значение документа не попадают вовсе: их приносит только движок. */
private const val IMPOSSIBLE_CHARS = "_|\\^{}<>"

/** Знаки, которыми набранное человеком значение не кончается. Точки и двоеточия здесь нет. */
private const val TRAILING_NOISE = ",;!'"

private val WORDS = Regex("""[\s]+""")

private fun balanced(text: String, open: Char, close: Char): Boolean =
    text.count { it == open } == text.count { it == close }

/** Латиница и кириллица внутри одного слова — движок выбирал букву по начертанию, а не по языку. */
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
