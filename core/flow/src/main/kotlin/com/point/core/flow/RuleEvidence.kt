package com.point.core.flow

fun AtomLayer.ruleEvidence(): Map<String, List<String>> {
    val evidence = LinkedHashMap<String, MutableList<String>>()
    fun mark(atom: Atom, rule: String) {
        val rules = evidence.getOrPut(atom.id) { mutableListOf() }
        if (rule !in rules) rules += rule
    }
    lines(atoms.filter { it.text.isNotBlank() }).forEach { line ->
        line.forEach { atom ->
            if (BARE_CLOCK.matches(atom.text.trim())) mark(atom, "clock-shaped")

            if (looksLikeTrackToken(atom.text)) mark(atom, "track-shaped")
        }
        cellRuns(line).forEach { run ->
            digitStretches(run).forEach { stretch ->
                trackWindows(stretch).forEach { window ->
                    window.forEach { mark(it, "track-shaped") }
                }
            }
        }
    }
    return evidence
}

internal fun cellRuns(line: List<Atom>): List<List<Atom>> {
    val runs = mutableListOf<MutableList<Atom>>()
    line.forEach { atom ->
        val prev = runs.lastOrNull()?.last()
        val split = prev != null &&
            atom.box.left - prev.box.right > maxOf(prev.box.height, atom.box.height) * CELL_GAP_HEIGHTS
        if (prev == null || split) runs += mutableListOf(atom) else runs.last() += atom
    }
    return runs
}

private fun digitStretches(run: List<Atom>): List<List<Atom>> {
    val stretches = mutableListOf<MutableList<Atom>>()
    var open = false
    run.forEach { atom ->
        if (atom.isDigitRun()) {
            if (!open) stretches += mutableListOf<Atom>().also { open = true }
            stretches.last() += atom
        } else {
            open = false
        }
    }
    return stretches
}

private fun Atom.isDigitRun(): Boolean =
    text.trim().let { t -> t.isNotEmpty() && t.all { it.isDigit() || it == ' ' } }

private fun trackWindows(stretch: List<Atom>): List<List<Atom>> {
    val windows = mutableListOf<List<Atom>>()
    var i = 0
    while (i < stretch.size) {
        var sum = 0
        var j = i
        while (j < stretch.size && sum < WAYBILL_DIGITS) {
            sum += stretch[j].text.count(Char::isDigit)
            j++
        }
        if (sum == WAYBILL_DIGITS) {
            windows += stretch.subList(i, j)
            i = j
        } else {
            i++
        }
    }
    return windows
}

private const val CELL_GAP_HEIGHTS = 1f
