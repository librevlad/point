package com.point.core.flow

sealed interface CellAnswer {

    data class Ids(val ids: List<String>, val text: String? = null) : CellAnswer

    data class Literal(val text: String) : CellAnswer
}

data class GroundedTable(
    val rows: List<List<String>>,
    val candidates: Map<Pair<Int, Int>, List<String>> = emptyMap(),

    val structural: Set<Pair<Int, Int>> = emptySet(),
)

fun AtomLayer.resolveCells(cells: List<List<CellAnswer>>): GroundedTable {
    val page = pageValues(text)
    val witness = pageWitnesses(cells, page)
    val rows = ArrayList<List<String>>(cells.size)
    val candidates = LinkedHashMap<Pair<Int, Int>, List<String>>()
    val structural = LinkedHashSet<Pair<Int, Int>>()
    cells.forEachIndexed { r, row ->
        rows += row.mapIndexed { c, cell ->
            var flagged = false
            val text = when (cell) {
                is CellAnswer.Literal -> {
                    val folded = pageFold(cell.text)
                    flagged = witness &&
                        cell.text.any { it.isDigit() } && folded.isNotEmpty() && folded !in page
                    cell.text
                }
                is CellAnswer.Ids -> {
                    val v = resolve(AtomAddress.ByIds(cell.ids))

                    val model = cell.text?.replace("⚠", "")?.replace("~~", "")?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    flagged = v.droppedIds.isNotEmpty() || v.disjoint

                    if (!flagged && v.atoms.isNotEmpty()) structural += r to c
                    when {
                        v.atoms.isEmpty() -> {
                            if (model != null) flagged = true
                            model ?: ""
                        }

                        model == null -> v.text

                        differsOnlyInNoise(v.text, model) -> cleanerReading(v.text, model)
                        normConsensus(model) == normConsensus(v.text) -> v.text

                        differsOnlyInAlphabet(v.text, model) -> model
                        isRepairOf(v.text, model) -> model
                        else -> {
                            flagged = true

                            val chosen = if (digitsOf(v.text) == digitsOf(model)) model else v.text
                            val other = if (chosen == model) v.text else model

                            listOf(chosen, other).distinct().filter { it.length <= 80 }
                                .takeIf { it.size >= 2 }
                                ?.let { candidates[r to c] = it }
                            chosen
                        }
                    }
                }
            }
            if (flagged && !text.contains('⚠')) "$text⚠" else text
        }
    }
    return GroundedTable(rows, candidates, structural)
}

private fun pageWitnesses(cells: List<List<CellAnswer>>, page: Set<String>): Boolean {
    val dictated = cells.asSequence().flatten()
        .filterIsInstance<CellAnswer.Literal>()
        .filter { cell -> cell.text.any(Char::isDigit) }
        .map { cell -> pageFold(cell.text) }
        .filter { it.isNotEmpty() }
        .toList()
    if (dictated.size < MIN_WITNESS_CELLS) return true
    return dictated.count { it in page } * WITNESS_SHARE_DIVISOR >= dictated.size
}

private const val MIN_WITNESS_CELLS = 8

private const val WITNESS_SHARE_DIVISOR = 5

fun AtomLayer.promptIndex(): String? {
    val named = atoms.filter { it.text.isNotBlank() }
    if (named.isEmpty() || named.size > MAX_PROMPT_ATOMS) return null
    if (symbolSoup(text)) return null
    val evidence = ruleEvidence()
    return lines(named).joinToString("\n") { line ->
        line.joinToString(" ") { atom ->
            val rules = evidence[atom.id]
            val attr = if (rules.isNullOrEmpty()) "" else " rule=" + rules.joinToString(",")
            "[${atom.id}$attr]${atom.text}"
        }
    }
}

private fun symbolSoup(text: String): Boolean {
    val nonSpace = text.count { !it.isWhitespace() }
    if (nonSpace == 0) return true
    val readable = text.count { it.isLetterOrDigit() }
    return readable.toDouble() / nonSpace < 0.6
}

private fun pageValues(text: String): Set<String> {
    val values = HashSet<String>()
    text.split('\n').forEach { line ->
        val tokens = line.split(WHITESPACE).map(::pageFold).filter { it.isNotEmpty() }
        tokens.indices.forEach { i ->
            val chain = StringBuilder()
            var j = i
            while (j < tokens.size && chain.length + tokens[j].length <= MAX_VALUE_LEN) {
                chain.append(tokens[j])
                values.add(chain.toString())
                j++
            }
        }
    }
    return values
}

private fun pageFold(s: String): String = normConsensus(s).replace("/", "").replace(":", "")

private val WHITESPACE = Regex("""\s+""")

private const val MAX_VALUE_LEN = 64

const val MAX_PROMPT_ATOMS = 600
