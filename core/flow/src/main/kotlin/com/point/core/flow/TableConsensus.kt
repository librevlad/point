package com.point.core.flow

/**
 * The reconciled table from several independent model reads (#200 ocr++). [rows] is the merged table —
 * plurality value per cell, with a trailing ⚠ on any cell the models disagreed on; [candidates] holds
 * the distinct readings for each disagreed `(row, col)` so the UI can offer them for one-tap picking.
 */
data class Consensus(
    val rows: List<List<String>>,
    val candidates: Map<Pair<Int, Int>, List<String>>,
)

/**
 * Vote each cell across [tables] (independent reads of the same table). Aligns by row/column index —
 * strong vision models produce the same structure for a clean table; a shorter read simply has no
 * value to contribute for the missing cells. A cell is clean iff every present read agrees; otherwise
 * it takes the plurality raw value, is flagged ⚠, and its distinct readings become candidates.
 */
fun reconcile(tables: List<List<List<String>>>): Consensus {
    val ts = tables.filter { it.isNotEmpty() }
    if (ts.size <= 1) return Consensus(ts.firstOrNull() ?: emptyList(), emptyMap())

    // Строки выравниваются ПО СОДЕРЖИМОМУ, а не по индексу (#294): модель, пропустившая
    // строку заголовка, сдвинута целиком, и голосование по индексу сравнивало её заголовок
    // со значением соседа — ложный ⚠ на каждой ячейке и дропдауны из разнородных чтений.
    val slots = alignRows(ts)
    val outRows = ArrayList<List<String>>(slots.size)
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()

    slots.forEachIndexed { r, slot ->
        val ncol = slot.mapNotNull { it?.size }.maxOrNull() ?: 0
        val row = ArrayList<String>(ncol)
        for (c in 0 until ncol) {
            // The vote itself is [agree] (#222, шаг 7) — same mechanics, no longer table-only.
            // What stays here is the table's own dressing: the ⚠ marker and the candidate cap.
            val verdict = agree(slot.mapNotNull { it?.getOrNull(c) })
            if (verdict == null) {
                row.add(""); continue
            }
            if (verdict.agreed) {
                row.add(verdict.value) // every present read agrees
            } else {
                row.add(if (verdict.value.contains('⚠')) verdict.value else "${verdict.value}⚠")
                candidates[r to c] = verdict.candidates.filter { it.length <= 80 }
            }
        }
        outRows.add(row)
    }
    return Consensus(outRows, candidates)
}

/**
 * Строки всех чтений, разложенные по слотам общей сетки (#294).
 *
 * Слот — одна строка документа: `slot[i]` — как её прочитала таблица `i`, либо `null`, если
 * это чтение строки не увидело. Пропущенная строка честно голосуется как **отсутствие**
 * ([agree] «ничего не прочитано ≠ спор»), а не как чужая строка.
 *
 * Выравнивание — наибольшая общая подпоследовательность по «похожести строк»
 * ([rowsSimilar]): порядок строк документа сохраняется всеми чтениями, поэтому перестановки
 * не ищутся — только пропуски и вставки. Первое чтение задаёт сетку, каждое следующее
 * пристраивается к ней, а его собственные находки становятся новыми слотами на своём месте.
 */
internal fun alignRows(tables: List<List<List<String>>>): List<List<List<String>?>> {
    var slots: MutableList<MutableList<List<String>?>> =
        tables.first().mapTo(mutableListOf()) { mutableListOf<List<String>?>(it) }
    for (t in 1 until tables.size) {
        val rows = tables[t]
        val grid = slots.map { slot -> slot.firstOrNull { it != null }!! }
        val next = mutableListOf<MutableList<List<String>?>>()
        // Ключ строки — если он есть — надёжнее похожести: рукописные пометки поверх бланка
        // меняют половину ячеек, но артикул остаётся артикулом.
        val key = keyColumns(grid, rows)
        val pairs = if (key != null) matchByKey(grid, rows, key) else matchRows(grid, rows)
        for ((slotIdx, rowIdx) in pairs) {
            when {
                slotIdx != null && rowIdx != null -> next += slots[slotIdx].also { it += rows[rowIdx] }
                slotIdx != null -> next += slots[slotIdx].also { it += null }
                // Строка, которой в сетке ещё не было: у прежних чтений её просто нет.
                rowIdx != null -> next += MutableList<List<String>?>(t) { null }.also { it += rows[rowIdx] }
            }
        }
        slots = next
    }
    return slots
}

/**
 * Ключевая колонка — столбец, по которому строки таблицы узнают друг друга (#294, эталонная
 * ведомость владельца).
 *
 * На реальной ведомости первая колонка — артикул (`11004`, `11006`, `11012`…), и он опознаёт
 * строку надёжнее, чем похожесть всей строки: рукописные пометки поверх бланка меняют половину
 * ячеек, строки перестают быть «похожими» и разъезжаются по разным слотам — в файле появляется
 * строка-фантом, а значения уезжают к соседям.
 *
 * Колонка считается ключевой, только если она **действительно ключ**: значения в ней уникальны
 * внутри каждого чтения и хотя бы половина из них встречается в обоих. Не нашли такую — работаем
 * по прежнему пути (похожесть строк), а не выдумываем ключ.
 */
private fun keyColumns(a: List<List<String>>, b: List<List<String>>): Pair<Int, Int>? {
    fun keys(t: List<List<String>>, c: Int) =
        t.mapNotNull { it.getOrNull(c)?.let(::normConsensus)?.takeIf { v -> v.isNotEmpty() } }
    val wa = a.maxOfOrNull { it.size } ?: 0
    val wb = b.maxOfOrNull { it.size } ?: 0
    var best: Pair<Int, Int>? = null
    var bestShared = 0
    // Пары столбцов, а не один и тот же индекс: на живой ведомости одно чтение отдало артикул
    // первой колонкой, другое — второй (первую заняла пустая колонка бланка), и ключ, искомый
    // по совпадающему индексу, не находился вовсе.
    for (ca in 0 until minOf(wa, MAX_KEY_SCAN)) {
        val ka = keys(a, ca)
        if (ka.size < MIN_KEYED_ROWS || ka.toSet().size != ka.size) continue
        for (cb in 0 until minOf(wb, MAX_KEY_SCAN)) {
            val kb = keys(b, cb)
            if (kb.size < MIN_KEYED_ROWS || kb.toSet().size != kb.size) continue
            // Почти все, а не половина: на «Итого» против «Всего» половина совпадений находится
            // случайно, и подписи строк выдали бы себя за идентификаторы (поймано тестом #294).
            val shared = ka.count { it in kb.toSet() }
            if (shared * 5 >= minOf(ka.size, kb.size) * 4 && shared > bestShared) {
                best = ca to cb
                bestShared = shared
            }
        }
    }
    return best
}

/** Дальше третьей колонки идентификатор строки не прячется, а перебор пар дорожает квадратично. */
private const val MAX_KEY_SCAN = 3

/** Ниже этого числа опознанных строк «ключ» — совпадение, а не свойство таблицы. */
private const val MIN_KEYED_ROWS = 5

/**
 * Пары «слот сетки ↔ строка чтения» по ключевой колонке: строки с одинаковым ключом — одна
 * строка документа, чем бы ни отличались остальные ячейки. Строки без ключа и с ключом, которого
 * нет у другой стороны, остаются на своих местах в порядке документа.
 */
private fun matchByKey(
    grid: List<List<String>>,
    rows: List<List<String>>,
    columns: Pair<Int, Int>,
): List<Pair<Int?, Int?>> {
    fun key(row: List<String>, c: Int) = row.getOrNull(c)?.let(::normConsensus)?.takeIf { it.isNotEmpty() }
    fun key(row: List<String>) = key(row, columns.first)
    val rowByKey = rows.indices.mapNotNull { i -> key(rows[i], columns.second)?.let { it to i } }.toMap()
    val used = mutableSetOf<Int>()
    val out = mutableListOf<Pair<Int?, Int?>>()
    grid.indices.forEach { g ->
        val match = key(grid[g])?.let(rowByKey::get)
        if (match != null && used.add(match)) out += g to match else out += g to null
    }
    // Строки чтения, не нашедшие своего ключа в сетке, — его собственные находки: они встают
    // после ближайшей уже сопоставленной строки, чтобы не всплыть в конце документа.
    rows.indices.filter { it !in used }.forEach { r ->
        val after = out.indexOfLast { it.second != null && it.second!! < r }
        if (after >= 0) out.add(after + 1, null to r) else out.add(0, null to r)
    }
    return out
}

/**
 * Пары «слот сетки ↔ строка чтения» в порядке документа: `null` с одной стороны — пропуск.
 * Классический LCS: длина совпадений максимизируется, порядок сохраняется.
 */
private fun matchRows(
    grid: List<List<String>>,
    rows: List<List<String>>,
): List<Pair<Int?, Int?>> {
    val n = grid.size
    val m = rows.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (rowsSimilar(grid[i], rows[j])) {
                lcs[i + 1][j + 1] + 1
            } else {
                maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
    }
    val raw = mutableListOf<Pair<Int?, Int?>>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            rowsSimilar(grid[i], rows[j]) -> { raw += i to j; i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { raw += i to null; i++ }
            else -> { raw += null to j; j++ }
        }
    }
    while (i < n) raw += i++ to null
    while (j < m) raw += null to j++
    return pairSubstitutions(raw)
}

/**
 * Соседние «только сетка» и «только чтение» — это ЗАМЕНА одной строки, а не потеря и находка
 * (ревью #294). Разорванная надвое, такая строка попадала в два слота и голосовалась в
 * одиночку — то есть консенсус выключался ровно там, где модели разошлись сильнее всего:
 * спор исчезал, ⚠ не ставился, а в файле появлялась лишняя строка.
 *
 * Классический diff называет это substitution; здесь она и восстанавливается, чтобы обе
 * версии строки встретились в одном слоте и рассудились [agree].
 */
private fun pairSubstitutions(ops: List<Pair<Int?, Int?>>): List<Pair<Int?, Int?>> {
    val out = mutableListOf<Pair<Int?, Int?>>()
    var k = 0
    while (k < ops.size) {
        val cur = ops[k]
        val next = ops.getOrNull(k + 1)
        val curIsGridOnly = cur.first != null && cur.second == null
        val curIsRowOnly = cur.first == null && cur.second != null
        val nextIsGridOnly = next != null && next.first != null && next.second == null
        val nextIsRowOnly = next != null && next.first == null && next.second != null
        when {
            curIsGridOnly && nextIsRowOnly -> { out += cur.first to next!!.second; k += 2 }
            curIsRowOnly && nextIsGridOnly -> { out += next!!.first to cur.second; k += 2 }
            else -> { out += cur; k++ }
        }
    }
    return out
}

/**
 * Одна ли это строка документа: большинство сопоставимых ячеек читаются одинаково после
 * свёртки формата ([normConsensus]). Сравниваются только позиции, где обе стороны непусты —
 * иначе короткое чтение строки не совпало бы с полным ни с одной.
 */
private fun rowsSimilar(a: List<String>, b: List<String>): Boolean {
    var comparable = 0
    var same = 0
    for (c in 0 until minOf(a.size, b.size)) {
        val x = normConsensus(a[c])
        val y = normConsensus(b[c])
        if (x.isEmpty() || y.isEmpty()) continue
        comparable++
        if (x == y) same++
    }
    // Строгое большинство, а не половина (ревью #294): в узкой таблице с повторяющимся
    // столбцом «1|5» и «2|5» совпадали ровно наполовину и склеивались в одну строку —
    // разные строки документа теряли друг друга. Половину теперь разбирает pairSubstitutions:
    // соседние односторонние слоты — это замена, и обе версии встречаются в одном слоте.
    return comparable > 0 && same * 2 > comparable
}
