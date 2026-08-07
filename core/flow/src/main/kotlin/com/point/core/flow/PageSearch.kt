package com.point.core.flow

data class PageMatch(

    val atoms: List<Atom>,

    val region: Box,
) {

    val ids: List<String> get() = atoms.map { it.id }

    val text: String get() = atoms.joinToString(" ") { it.text }
}

fun AtomLayer.findOnPage(query: String, page: Int = 0): List<PageMatch> {
    val needle = foldForSearch(query)
    if (needle.isEmpty()) return emptyList()
    return lines(atoms.filter { it.page == page })
        .flatMap { line -> matchesInLine(line, needle) }

        .distinctBy { it.ids }
}

private fun matchesInLine(line: List<Atom>, needle: String): List<PageMatch> {
    val raw = StringBuilder()
    val rawOwner = ArrayList<Int>()
    line.forEachIndexed { index, atom ->
        raw.append(atom.text)
        repeat(atom.text.length) { rawOwner.add(index) }
    }
    val hay = StringBuilder()
    val owner = ArrayList<Int>()
    foldForSearch(raw) { ch, at ->
        hay.append(ch)
        owner.add(rawOwner[at])
    }
    val text = hay.toString()
    val found = mutableListOf<PageMatch>()
    var from = 0
    while (from <= text.length - needle.length) {
        val at = text.indexOf(needle, from)
        if (at < 0) break
        val hit = (at until at + needle.length).map { owner[it] }.distinct().map { line[it] }
        found += PageMatch(hit, hit.map { it.box }.reduce(Box::union))

        from = at + needle.length
    }
    return found
}

fun isSearchable(query: String): Boolean = foldForSearch(query).isNotEmpty()

internal fun foldForSearch(raw: CharSequence, onKeep: (Char, Int) -> Unit) {
    var prevKept: Char? = null
    for (i in raw.indices) {
        val c = raw[i].lowercaseChar()
        val kept: Char? = when {
            c.isWhitespace() || c in SEARCH_NOISE -> null
            c == '.' || c == ',' ->
                if (prevKept?.isDigit() == true && nextNonSpaceIsDigit(raw, i)) '.' else null
            else -> c
        }
        if (kept != null) {
            onKeep(kept, i)
            prevKept = kept
        }
    }
}

private fun nextNonSpaceIsDigit(raw: CharSequence, at: Int): Boolean {
    var j = at + 1
    while (j < raw.length && raw[j].isWhitespace()) j++
    return j < raw.length && raw[j].isDigit()
}

internal fun foldForSearch(raw: CharSequence): String =
    buildString { foldForSearch(raw) { ch, _ -> append(ch) } }

private const val SEARCH_NOISE = "-–—«»„“”\"'‘’⚠"

fun foundOnPageLabel(matches: Int): String =
    if (matches == 0) "Ничего не нашлось" else "Нашлось $matches ${placesWord(matches)}"

private fun placesWord(n: Int): String {
    val hundred = n % 100
    val ten = n % 10
    return when {
        hundred in 11..14 -> "мест"
        ten == 1 -> "место"
        ten in 2..4 -> "места"
        else -> "мест"
    }
}
