package com.point.core.flow

data class Consensus(
    val rows: List<List<String>>,
    val candidates: Map<Pair<Int, Int>, List<String>>,

    val sources: Int = 1,
)

fun reconcile(tables: List<List<List<String>>>): Consensus {
    val ts = tables.filter { it.isNotEmpty() }
    if (ts.size <= 1) return Consensus(ts.firstOrNull() ?: emptyList(), emptyMap(), sources = ts.size)

    val slots = alignRows(ts)

    if (slots.size >= MIN_SLOTS_TO_JUDGE_ALIGNMENT && agreedShare(slots, ts.size) < MIN_ALIGNED_SHARE) {

        return Consensus(medoid(ts), emptyMap(), sources = 1)
    }

    val outRows = ArrayList<List<String>>(slots.size)
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()

    slots.forEachIndexed { r, slot ->

        val seenByEveryone = slot.count { it != null } == ts.size

        val columns = columnsOf(slot.filterNotNull())
        val row = ArrayList<String>(columns.size)
        columns.forEachIndexed { c, readings ->

            val verdict = agree(readings)
            if (verdict == null) {
                row.add(""); return@forEachIndexed
            }
            if (verdict.agreed && (seenByEveryone || verdict.value.isBlank())) {
                row.add(verdict.value)
            } else {
                row.add(if (verdict.value.contains('⚠')) verdict.value else "${verdict.value}⚠")

                if (!verdict.agreed) candidates[r to c] = verdict.candidates.filter { it.length <= 80 }
            }
        }
        outRows.add(row)
    }
    return Consensus(outRows, candidates, sources = ts.size)
}

private fun medoid(tables: List<List<List<String>>>): List<List<String>> {
    if (tables.size < 3) return tables.first()
    val values = tables.map { t -> t.flatten().map(::normConsensus).filter { it.isNotEmpty() }.toSet() }
    fun share(a: Set<String>, b: Set<String>): Double {
        val union = (a + b).size
        return if (union == 0) 0.0 else a.intersect(b).size.toDouble() / union
    }
    val best = values.indices.maxByOrNull { i ->
        values.indices.filter { it != i }.map { share(values[i], values[it]) }.average()
    } ?: 0
    return tables[best]
}

private fun agreedShare(slots: List<List<List<String>?>>, sources: Int): Double =
    if (slots.isEmpty()) 1.0 else slots.count { slot -> slot.count { it != null } == sources }.toDouble() / slots.size

private const val MIN_ALIGNED_SHARE = 2.0 / 3.0

private const val MIN_SLOTS_TO_JUDGE_ALIGNMENT = 8

private fun columnsOf(readings: List<List<String>>): List<List<String>> {
    val base = readings.firstOrNull().orEmpty()
    val columns = base.mapTo(mutableListOf()) { mutableListOf(it) }
    readings.drop(1).forEach { row ->
        val places = alignCells(base, row)
        row.forEachIndexed { j, value ->
            val c = if (places == null) j else places[j]
            if (c == null) {
                if (value.isNotBlank()) columns += mutableListOf(value)
            } else {
                while (columns.size <= c) columns += mutableListOf<String>()
                columns[c] += value
            }
        }
    }
    return columns
}

private fun alignCells(base: List<String>, row: List<String>): List<Int?>? {
    fun same(a: String?, b: String?): Boolean {
        val x = a?.let(::normConsensus).orEmpty()
        return x.isNotEmpty() && x == b?.let(::normConsensus)
    }
    val byIndex = row.indices.count { same(base.getOrNull(it), row[it]) }

    val matched = lcsOps(base.size, row.size) { i, j -> same(base[i], row[j]) }
    val ops = pairSubstitutions(matched) { _, _ -> true }
    val byContent = ops.count { (i, j) -> i != null && j != null && same(base[i], row[j]) }
    if (byContent <= byIndex) return null
    val places = arrayOfNulls<Int>(row.size)
    ops.forEach { (i, j) -> if (j != null) places[j] = i }
    return places.toList()
}

internal fun alignRows(tables: List<List<List<String>>>): List<List<List<String>?>> {
    var slots: MutableList<MutableList<List<String>?>> =
        tables.first().mapTo(mutableListOf()) { mutableListOf<List<String>?>(it) }
    for (t in 1 until tables.size) {
        val rows = tables[t]
        val grid = slots.map { slot -> slot.firstOrNull { it != null }!! }
        val next = mutableListOf<MutableList<List<String>?>>()

        val key = keyColumns(grid, rows)
        val pairs = if (key != null) matchByKey(grid, rows, key) else matchRows(grid, rows)
        for ((slotIdx, rowIdx) in pairs) {
            when {
                slotIdx != null && rowIdx != null -> next += slots[slotIdx].also { it += rows[rowIdx] }
                slotIdx != null -> next += slots[slotIdx].also { it += null }

                rowIdx != null -> next += MutableList<List<String>?>(t) { null }.also { it += rows[rowIdx] }
            }
        }
        slots = next
    }
    return slots
}

private fun keyColumns(a: List<List<String>>, b: List<List<String>>): Pair<Int, Int>? {
    fun keys(t: List<List<String>>, c: Int) =
        t.mapNotNull { it.getOrNull(c)?.let(::normConsensus)?.takeIf { v -> v.isNotEmpty() } }
    val wa = a.maxOfOrNull { it.size } ?: 0
    val wb = b.maxOfOrNull { it.size } ?: 0
    var best: Pair<Int, Int>? = null
    var bestShared = 0

    for (ca in 0 until minOf(wa, MAX_KEY_SCAN)) {
        val ka = keys(a, ca)
        if (ka.size < MIN_KEYED_ROWS || ka.toSet().size != ka.size) continue
        for (cb in 0 until minOf(wb, MAX_KEY_SCAN)) {
            val kb = keys(b, cb)
            if (kb.size < MIN_KEYED_ROWS || kb.toSet().size != kb.size) continue

            val shared = ka.count { it in kb.toSet() }
            if (shared * 5 >= minOf(ka.size, kb.size) * 4 && shared > bestShared) {
                best = ca to cb
                bestShared = shared
            }
        }
    }
    return best
}

private const val MAX_KEY_SCAN = 3

private const val MIN_KEYED_ROWS = 5

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

    rows.indices.filter { it !in used }.forEach { r ->
        val after = out.indexOfLast { it.second != null && it.second!! < r }
        if (after >= 0) out.add(after + 1, null to r) else out.add(0, null to r)
    }
    return out
}

private fun matchRows(
    grid: List<List<String>>,
    rows: List<List<String>>,
): List<Pair<Int?, Int?>> {
    val byId = rowsWithSameId(grid, rows)
    return pairSubstitutions(
        lcsOps(grid.size, rows.size) { i, j -> byId[i] == j || rowsSimilar(grid[i], rows[j]) },
    ) { i, j -> sameRowPossible(grid[i], rows[j]) }
}

private fun rowsWithSameId(grid: List<List<String>>, rows: List<List<String>>): Map<Int, Int> {
    val ofGrid = uniqueRowIds(grid)
    val ofRows = uniqueRowIds(rows)
    val pairs = ofGrid.mapNotNull { (id, g) -> ofRows[id]?.let { r -> g to r } }.distinct()
    val gridSeen = pairs.groupingBy { it.first }.eachCount()
    val rowSeen = pairs.groupingBy { it.second }.eachCount()
    return pairs.filter { gridSeen[it.first] == 1 && rowSeen[it.second] == 1 }.toMap()
}

private fun uniqueRowIds(table: List<List<String>>): Map<String, Int> {
    val rowsOf = LinkedHashMap<String, MutableSet<Int>>()
    table.forEachIndexed { r, row ->
        row.forEach { cell ->
            val value = normConsensus(cell)
            if (ID_SHAPED.matches(value)) rowsOf.getOrPut(value) { mutableSetOf() }.add(r)
        }
    }
    return rowsOf.filterValues { it.size == 1 }.mapValues { it.value.first() }
}

private fun lcsOps(n: Int, m: Int, similar: (Int, Int) -> Boolean): List<Pair<Int?, Int?>> {
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (similar(i, j)) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val raw = mutableListOf<Pair<Int?, Int?>>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            similar(i, j) -> { raw += i to j; i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { raw += i to null; i++ }
            else -> { raw += null to j; j++ }
        }
    }
    while (i < n) raw += i++ to null
    while (j < m) raw += null to j++
    return raw
}

private fun sameRowPossible(a: List<String>, b: List<String>): Boolean {
    for (c in 0 until minOf(a.size, b.size)) {
        val x = normConsensus(a[c])
        val y = normConsensus(b[c])
        if (x.isEmpty() || y.isEmpty()) continue
        if (!ID_SHAPED.matches(x) && !ID_SHAPED.matches(y)) continue
        if (x != y) return false
    }
    return true
}

private val ID_SHAPED = Regex("""\d{3,6}""")

private fun pairSubstitutions(
    ops: List<Pair<Int?, Int?>>,
    plausible: (Int, Int) -> Boolean,
): List<Pair<Int?, Int?>> {
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
            curIsGridOnly && nextIsRowOnly && plausible(cur.first!!, next!!.second!!) ->
                { out += cur.first to next.second; k += 2 }
            curIsRowOnly && nextIsGridOnly && plausible(next!!.first!!, cur.second!!) ->
                { out += next.first to cur.second; k += 2 }
            else -> { out += cur; k++ }
        }
    }
    return out
}

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

    return comparable > 0 && same * 2 > comparable
}
