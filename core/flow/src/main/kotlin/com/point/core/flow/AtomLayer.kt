package com.point.core.flow

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top
    val width: Float get() = right - left

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun intersects(other: Box): Boolean =
        left <= other.right && other.left <= right && top <= other.bottom && other.top <= bottom

    fun union(other: Box): Box =
        Box(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

data class Atom(
    val id: String,
    val text: String,
    val box: Box,

    val confidence: Float = 1f,

    val reader: String = "",

    val readerVersion: String = "",

    val page: Int = 0,
)

class AtomLayer(
    val atoms: List<Atom>,

    internal val readerText: String? = null,

    val transform: FrameTransform? = null,

    val incomplete: String? = null,
) {

    fun atomsIn(region: Box): List<Atom> =
        atoms.filter { region.contains(it.box.centerX, it.box.centerY) }

    fun textIn(region: Box): String =
        readingOrder(atomsIn(region)).joinToString(" ") { it.text }

    fun readingOrder(subset: List<Atom> = atoms): List<Atom> = lines(subset).flatten()

    fun doubtful(below: Float): List<Atom> = atoms.filter { it.confidence < below }

    /**
     * Текст ридера побеждает пересобранный, пока страница одноколоночная: ридер знает про
     * переносы и пунктуацию больше, чем склейка слов.
     *
     * Но колонки ридер отдаёт строкой поперёк страницы (#747): просвет между отправителем и
     * получателем виден только по геометрии. Нашлись колонки — читаем по ним, иначе весь
     * разбор ниже опирается на строку, которой на наклейке нет.
     */
    val text: String
        get() {
            val rebuilt = lines(atoms)
            val reader = readerText
            if (reader != null && !splitIntoColumns(rebuilt)) return reader
            return rebuilt.joinToString("\n") { line -> line.joinToString(" ") { it.text } }
        }

    private fun splitIntoColumns(rebuilt: List<List<Atom>>): Boolean =
        atoms.isNotEmpty() && rebuilt.size > rowsOf(placed(atoms)).size

    /**
     * Строки документа — с оглядкой на колонки (#747).
     *
     * Прежде строка собиралась через всю ширину страницы, и на почтовой наклейке «Тарасенко
     * Світлана Сергіївна» из левого столбца склеивалась с «Думброван Олександр» из правого:
     * отправитель и получатель смешивались, а имя получателя разрывалось. Владелец, замерив
     * разбор: «структура наша боль».
     *
     * Теперь страница сначала делится на столбцы по широким пустым просветам, и каждый
     * читается сверху донизу целиком. Блок во всю ширину — шапка, подвал — остаётся строкой
     * и разделяет столбцы выше и ниже себя: две таблицы подряд не сливаются в одну.
     */
    private fun placed(subset: List<Atom>): List<Pair<Atom, Box>> =
        subset.map { it to (transform?.toUpright(it.box) ?: it.box) }

    fun lines(subset: List<Atom> = atoms): List<List<Atom>> = blocksOf(subset).flatten()

    /**
     * Блоки страницы: столбец полосы целиком, а блок во всю ширину — сам по себе.
     *
     * Найденное в одном блоке относится к одному и тому же (#747): телефон под именем
     * отправителя принадлежит отправителю, а не получателю в соседнем столбце.
     */
    fun blocks(subset: List<Atom> = atoms): List<List<Atom>> = blocksOf(subset).map { it.flatten() }

    /**
     * Текст страницы блоками: столбец отдельно, шапка и подвал отдельно (#768).
     *
     * Нужен там, где важна принадлежность строки, а не только её порядок: подпись колонки
     * должна остаться при своей колонке.
     */
    fun blockTexts(subset: List<Atom> = atoms): List<String> =
        blocksOf(subset).map { block ->
            block.joinToString("\n") { line -> line.joinToString(" ") { it.text } }
        }

    private fun blocksOf(subset: List<Atom>): List<List<List<Atom>>> {
        val placed = placed(subset)
        if (placed.isEmpty()) return emptyList()

        val bands = bandsOf(rowsOf(placed))
        val gaps = bands.map { columnGaps(it) }
        bindCaptions(bands, gaps)

        val out = mutableListOf<List<List<Atom>>>()
        bands.forEachIndexed { index, band ->
            if (band.isEmpty()) return@forEachIndexed
            out += if (gaps[index].isEmpty()) listOf(band.map { ordered(it) }) else columnsOf(band, gaps[index])
        }
        return out
    }

    private fun bandsOf(rows: List<List<Pair<Atom, Box>>>): List<MutableList<List<Pair<Atom, Box>>>> {
        val bands = mutableListOf<MutableList<List<Pair<Atom, Box>>>>()
        var band = mutableListOf<List<Pair<Atom, Box>>>()

        rows.forEach { row ->
            // Полоса живёт, пока столбцы в ней различимы. Строка во всю ширину — шапка,
            // подвал, итог — закрывает полосу: ниже начинается другая раскладка.
            val grown = band + listOf(row)
            when {
                band.isEmpty() -> band = mutableListOf(row)
                columnGaps(grown).isNotEmpty() -> band = grown.toMutableList()
                else -> { bands += band; band = mutableListOf(row) }
            }
        }
        if (band.isNotEmpty()) bands += band

        return bands
    }

    /**
     * Подпись колонки возвращается своей колонке (#768).
     *
     * Строка над полосой просвета не образует: подпереть столбец ей нечем, и она выпадает
     * отдельным блоком. Из-за этого «КОМУ:» на снятой с наклоном наклейке оказывалась выше
     * всех столбцов, модель читала её сверху вниз и приписывала левому — отправитель и
     * получатель менялись местами.
     *
     * Полоса забирает такую строку, если строка ложится на её столбцы: ни одно слово не
     * стоит поперёк просвета. Дальше строку разбирают те же столбцы — «ВІД:» уходит налево,
     * «КОМУ:» направо. Шапка страницы этим и отсекается: она идёт поперёк просвета.
     */
    private fun bindCaptions(
        bands: List<MutableList<List<Pair<Atom, Box>>>>,
        gaps: List<List<ClosedFloatingPointRange<Float>>>,
    ) {
        bands.indices.forEach { index ->
            val columns = gaps[index]
            if (columns.isEmpty()) return@forEach
            val line = lineHeight(bands[index].flatten())
            if (line <= 0f) return@forEach

            var above = index - 1
            while (above >= 0 && bands[above].size == 1 && gaps[above].isEmpty()) {
                val caption = bands[above].single()
                if (!sitsInColumns(caption, columns, line)) break
                if (!closeAbove(caption, bands[index].first(), line)) break
                bands[above].clear()
                bands[index].add(0, caption)
                above--
            }
        }
    }

    /**
     * Строка ложится на столбцы полосы: ни одно её слово не стоит поперёк просвета.
     *
     * Судят только слова. На настоящей наклейке поперёк просвета висела волосяная черта в три
     * точки шириной при высоте строки — сгиб бумаги, прочитанный уверенно (0.83). По ней
     * строка «КОМУ:» и оставалась без своей колонки: уверенность такую черту не отсеивает,
     * а ширина отсеивает.
     */
    private fun sitsInColumns(
        row: List<Pair<Atom, Box>>,
        gaps: List<ClosedFloatingPointRange<Float>>,
        line: Float,
    ): Boolean {
        val words = row.filter { (atom, box) ->
            atom.confidence >= CONFIDENT_ENOUGH && box.width >= line * WORD_MIN_WIDTH
        }
        if (words.isEmpty()) return false

        return words.none { (_, box) ->
            gaps.any { gap -> box.right > gap.start && box.left < gap.endInclusive }
        }
    }

    /** Подпись стоит вплотную над своей полосой, а не где-то выше по странице. */
    private fun closeAbove(
        caption: List<Pair<Atom, Box>>,
        top: List<Pair<Atom, Box>>,
        line: Float,
    ): Boolean =
        top.minOf { (_, box) -> box.top } - caption.maxOf { (_, box) -> box.bottom } <= line * CAPTION_REACH

    /** Высота строки полосы — по уверенно прочитанным словам, чтобы её не занижали крапины. */
    private fun lineHeight(placed: List<Pair<Atom, Box>>): Float {
        val heights = placed.filter { (atom, _) -> atom.confidence >= CONFIDENT_ENOUGH }
            .map { (_, box) -> box.height }
            .sorted()
        return if (heights.isEmpty()) 0f else heights[heights.size / 2]
    }

    /** Сырые строки: слова на одной высоте. Столбцы здесь ещё не различаются. */
    private fun rowsOf(placed: List<Pair<Atom, Box>>): List<List<Pair<Atom, Box>>> {
        val rows = mutableListOf<MutableList<Pair<Atom, Box>>>()
        placed.sortedBy { (_, box) -> box.centerY }.forEach { entry ->
            val (_, box) = entry
            val row = rows.lastOrNull()
            val sameRow = row != null &&
                kotlin.math.abs(row.last().second.centerY - box.centerY) <= box.height / 2f
            if (sameRow) row.add(entry) else rows.add(mutableListOf(entry))
        }
        return rows
    }

    private fun ordered(row: List<Pair<Atom, Box>>): List<Atom> =
        row.sortedBy { (_, box) -> box.left }.map { (atom, _) -> atom }

    /**
     * Просветы между столбцами: полосы по оси X, где во всей полосе нет ни одного слова.
     *
     * Широкий просвет сам по себе столбцом не делает. «Артикул · Наименование · Кол» и
     * «ТТН   20 4514 9154» разнесены так же далеко, но это одна строка: ячейки таблицы и
     * подпись со значением. Поэтому просвет считается границей столбцов, только если
     * выполняются оба условия:
     *
     * 1. по обе стороны стоят блоки текста, а не ячейки — каждый заметно шире самого просвета;
     * 2. он держится на нескольких строках сразу, а не на одной.
     */
    private fun columnGaps(band: List<List<Pair<Atom, Box>>>): List<ClosedFloatingPointRange<Float>> {

        // Где колонка — решают уверенно прочитанные слова. На настоящей наклейке по левому
        // полю висели обрывки распознавания — «ez», «ia», «=)» с уверенностью 0.0–0.4, —
        // и столбец вставал по ним, а настоящий просвет между отправителем и получателем
        // оставался незамеченным (#747).
        val sure = band.map { row -> row.filter { (atom, _) -> atom.confidence >= CONFIDENT_ENOUGH } }
            .filter { it.isNotEmpty() }
        val solid = if (sure.size >= MIN_ROWS_ACROSS) sure else band
        val placed = solid.flatten()
        val heights = placed.map { (_, box) -> box.height }.sorted()
        val line = heights[heights.size / 2].takeIf { it > 0f } ?: return emptyList()
        val minGap = line * COLUMN_GAP_IN_LINES

        val spans = placed.map { (_, box) -> box.left to box.right }.sortedBy { it.first }
        val gaps = mutableListOf<ClosedFloatingPointRange<Float>>()
        var edge = spans.first().second
        spans.forEach { (left, right) ->
            if (left - edge > minGap) gaps += edge..left
            edge = maxOf(edge, right)
        }
        return blocksApart(gaps.filter { gap -> rowsAcross(solid, gap) >= MIN_ROWS_ACROSS }, placed)
    }

    /** На скольких строках полосы просвет разделяет написанное, а не обрывается краем строки. */
    private fun rowsAcross(band: List<List<Pair<Atom, Box>>>, gap: ClosedFloatingPointRange<Float>): Int =
        band.count { row ->
            row.any { (_, box) -> box.right <= gap.start } && row.any { (_, box) -> box.left >= gap.endInclusive }
        }

    /**
     * Оставить только те просветы, по обе стороны которых написанное шире самого просвета:
     * колонка — это блок текста, а ячейка таблицы отделена больше, чем занимает сама.
     */
    private fun blocksApart(
        gaps: List<ClosedFloatingPointRange<Float>>,
        placed: List<Pair<Atom, Box>>,
    ): List<ClosedFloatingPointRange<Float>> {
        var kept = gaps
        while (kept.isNotEmpty()) {
            val widths = blockWidths(kept, placed)
            val weakest = kept.indices.minByOrNull { i ->
                minOf(widths[i], widths[i + 1]) / (kept[i].endInclusive - kept[i].start)
            } ?: break
            val gap = kept[weakest]
            val narrower = minOf(widths[weakest], widths[weakest + 1])
            if (narrower >= (gap.endInclusive - gap.start) * COLUMN_TO_GAP) break
            kept = kept.filterIndexed { i, _ -> i != weakest }
        }
        return kept
    }

    private fun blockWidths(
        gaps: List<ClosedFloatingPointRange<Float>>,
        placed: List<Pair<Atom, Box>>,
    ): List<Float> {
        val edges = edgesOf(gaps)
        return List(edges.size - 1) { i ->
            val block = placed.filter { (_, box) -> columnOf(box, edges) == i }
            if (block.isEmpty()) 0f else block.maxOf { it.second.right } - block.minOf { it.second.left }
        }
    }

    private fun edgesOf(gaps: List<ClosedFloatingPointRange<Float>>): List<Float> =
        listOf(Float.NEGATIVE_INFINITY) + gaps.map { it.endInclusive } + Float.POSITIVE_INFINITY

    /** Полоса из нескольких строк: сначала левый столбец целиком, потом соседний. */
    private fun columnsOf(
        band: List<List<Pair<Atom, Box>>>,
        gaps: List<ClosedFloatingPointRange<Float>>,
    ): List<List<List<Atom>>> {
        val edges = edgesOf(gaps)
        val columns = List(edges.size - 1) { mutableListOf<List<Pair<Atom, Box>>>() }

        band.forEach { row ->
            row.groupBy { (_, box) -> columnOf(box, edges) }.forEach { (index, part) ->
                columns[index] += listOf(part)
            }
        }
        return columns.filter { it.isNotEmpty() }.map { column -> column.map { row -> ordered(row) } }
    }

    private fun columnOf(box: Box, edges: List<Float>): Int {
        for (i in 0 until edges.size - 1) {
            if (box.centerX >= edges[i] && box.centerX < edges[i + 1]) return i
        }
        return edges.size - 2
    }

    companion object {

        /** Столбец отделён просветом в несколько строчных высот, а не пробелом между словами. */
        const val COLUMN_GAP_IN_LINES = 1.5f

        /** Написанное в столбце шире просвета, который его отделяет: это блок, а не ячейка. */
        const val COLUMN_TO_GAP = 2f

        /** Столбец стоит на нескольких строках: на одной это подпись со значением рядом. */
        const val MIN_ROWS_ACROSS = 2

        /** Обрывок распознавания — не слово страницы и не решает, где проходит столбец. */
        const val CONFIDENT_ENOUGH = 0.6f

        /** Подпись стоит вплотную над своей полосой: дальше это уже другая часть страницы. */
        const val CAPTION_REACH = 1.5f

        /** Волосяная черта поперёк просвета — сгиб или линейка бланка, а не слово страницы. */
        const val WORD_MIN_WIDTH = 0.15f
    }
}
