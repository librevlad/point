package com.point.core.flow

enum class BlockRole {

    TITLE,

    FIELD,

    TABLE,

    TOTALS,

    NOTE,

    SIGN,

    CHROME,

    UNREAD,
}

sealed interface BlockContent {
    data class Text(val cell: CellAnswer) : BlockContent

    data class Grid(val cells: List<List<CellAnswer>>, val headerRows: Int) : BlockContent
}

data class BlockAnswer(
    val role: BlockRole,
    val label: CellAnswer? = null,
    val content: BlockContent,
)

enum class DocScope {

    FULL,

    VIEWPORT,

    CROPPED,
}

data class LayoutAnswer(val blocks: List<BlockAnswer>, val scope: DocScope? = null)

data class DocumentBlock(
    val role: BlockRole,

    val label: String,

    val text: String,
    val grid: GroundedTable?,

    val headerRows: Int,

    val ids: Set<String>,

    val flagged: Boolean,
)

data class DocumentLayout(
    val blocks: List<DocumentBlock>,
    val scope: DocScope?,

    val uncovered: List<Atom>,

    val coverage: Float?,
)

val DocumentLayout.grid: GroundedTable? get() = gridBlock()?.grid

val DocumentLayout.gridHeaderRows: Int get() = gridBlock()?.headerRows ?: 0

private fun DocumentLayout.gridBlock(): DocumentBlock? =
    gridIndex().takeIf { it >= 0 }?.let { blocks[it] }

private fun DocumentLayout.gridIndex(): Int =
    blocks.indexOfFirst { it.role == BlockRole.TABLE && it.grid != null }
        .takeIf { it >= 0 }
        ?: blocks.indexOfFirst { it.grid != null && it.role.isContent }

private val BlockRole.isContent: Boolean
    get() = this != BlockRole.CHROME && this != BlockRole.UNREAD

val DocumentLayout.unreadWords: Int get() = wordsIn(BlockRole.UNREAD)

val DocumentLayout.chromeWords: Int get() = wordsIn(BlockRole.CHROME)

val DocumentLayout.readWords: Int get() = wordsWhere { it.isContent }

private fun DocumentLayout.wordsIn(role: BlockRole): Int = wordsWhere { it == role }

private fun DocumentLayout.wordsWhere(role: (BlockRole) -> Boolean): Int =
    blocks.filter { role(it.role) }.sumOf { block ->
        block.ids.size.takeIf { it > 0 }
            ?: block.grid?.rows?.sumOf { row -> row.sumOf { it.wordCount() } }
            ?: block.text.wordCount()
    }

private fun String.wordCount(): Int = split(' ', '\n', '\t').count { it.isNotBlank() }

fun DocumentLayout.withGrid(grid: GroundedTable, headerRows: Int = gridHeaderRows): DocumentLayout {
    val at = gridIndex()
    if (at < 0) {
        if (grid.rows.isEmpty()) return this
        val table = DocumentBlock(
            BlockRole.TABLE, label = "", text = "", grid = grid,
            headerRows = headerRows, ids = emptySet(), flagged = false,
        )

        val head = blocks.takeWhile { it.role != BlockRole.UNREAD }
        return copy(blocks = head + table + blocks.drop(head.size))
    }
    val updated = blocks[at].copy(grid = grid, headerRows = headerRows)
    return copy(blocks = blocks.toMutableList().also { it[at] = updated })
}

fun AtomLayer.resolveLayout(answer: LayoutAnswer): DocumentLayout = buildLayout(answer, this)

fun literalLayout(answer: LayoutAnswer): DocumentLayout = buildLayout(answer, null)

private class Slice(val label: Int?, val text: Int?, val gridFrom: Int, val gridTo: Int)

private fun buildLayout(answer: LayoutAnswer, layer: AtomLayer?): DocumentLayout {
    val plan = ArrayList<List<CellAnswer>>()
    val slices = answer.blocks.map { block ->
        val label = block.label?.let { plan.add(listOf(it)); plan.size - 1 }
        when (val content = block.content) {
            is BlockContent.Text -> {
                plan.add(listOf(content.cell))
                Slice(label, plan.size - 1, 0, 0)
            }
            is BlockContent.Grid -> {
                val from = plan.size
                plan.addAll(content.cells)
                Slice(label, null, from, plan.size)
            }
        }
    }
    val resolved = layer?.resolveCells(plan) ?: literalCells(plan)
    val index = layer?.atoms?.associateBy { it.id }.orEmpty()

    val blocks = answer.blocks.mapIndexed { i, block ->
        val slice = slices[i]
        val label = slice.label?.let { resolved.rows[it].firstOrNull() }.orEmpty()
        val text = slice.text?.let { resolved.rows[it].firstOrNull() }.orEmpty()
        val grid = if (block.content is BlockContent.Grid) {
            GroundedTable(
                rows = resolved.rows.subList(slice.gridFrom, slice.gridTo).toList(),
                candidates = resolved.candidates.shiftedTo(slice.gridFrom, slice.gridTo),
                structural = resolved.structural.shiftedTo(slice.gridFrom, slice.gridTo),
            )
        } else {
            null
        }
        val claimed = block.claimedIds(index)
        DocumentBlock(
            role = block.role,
            label = label,
            text = text,
            grid = grid,
            headerRows = (block.content as? BlockContent.Grid)?.headerRows?.coerceAtLeast(0) ?: 0,
            ids = claimed,
            flagged = label.contains('⚠') || text.contains('⚠') ||
                grid?.rows?.any { row -> row.any { it.contains('⚠') } } == true,
        )
    }

    val named = layer?.atoms.orEmpty().filter { it.text.isNotBlank() }
    val claimed = blocks.flatMapTo(HashSet()) { it.ids }

    val addressed = claimed.isNotEmpty()
    val uncovered = if (addressed) named.filter { it.id !in claimed } else emptyList()
    val content = blocks.filter { it.role != BlockRole.CHROME && it.role != BlockRole.UNREAD }
        .flatMapTo(HashSet()) { it.ids }
    val coverage = when {
        !addressed || named.isEmpty() -> null
        else -> named.count { it.id in content }.toFloat() / named.size
    }

    val tail = if (uncovered.isEmpty() || layer == null) {
        emptyList()
    } else {
        listOf(
            DocumentBlock(
                role = BlockRole.UNREAD,
                label = "",
                text = "",
                grid = GroundedTable(
                    layer.lines(uncovered).map { line -> listOf(line.joinToString(" ") { it.text }) },
                ),
                headerRows = 0,
                ids = uncovered.mapTo(HashSet()) { it.id },
                flagged = false,
            ),
        )
    }
    return DocumentLayout(blocks + tail, answer.scope, uncovered, coverage)
}

private fun BlockAnswer.claimedIds(index: Map<String, Atom>): Set<String> {
    val out = LinkedHashSet<String>()
    fun take(cell: CellAnswer) {
        if (cell is CellAnswer.Ids) cell.ids.filterTo(out) { it in index }
    }
    label?.let(::take)
    when (val c = content) {
        is BlockContent.Text -> take(c.cell)
        is BlockContent.Grid -> c.cells.forEach { row -> row.forEach(::take) }
    }
    return out
}

private fun <T> Map<Pair<Int, Int>, T>.shiftedTo(from: Int, to: Int): Map<Pair<Int, Int>, T> =
    filterKeys { it.first in from until to }
        .mapKeys { (key, _) -> (key.first - from) to key.second }

private fun Set<Pair<Int, Int>>.shiftedTo(from: Int, to: Int): Set<Pair<Int, Int>> =
    filter { it.first in from until to }.mapTo(LinkedHashSet()) { (it.first - from) to it.second }

private fun literalCells(cells: List<List<CellAnswer>>): GroundedTable = GroundedTable(
    cells.map { row ->
        row.map { cell ->
            when (cell) {
                is CellAnswer.Literal -> cell.text
                is CellAnswer.Ids -> {
                    val text = cell.text?.trim().orEmpty()
                    if (text.contains('⚠')) text else "$text⚠"
                }
            }
        }
    },
)

const val META_TABLE_GRID = "table.grid"

const val META_TABLE_HEADER = "table.header"

const val META_TABLE_COVERED = "table.covered"

const val META_TABLE_SCOPE = "table.scope"

const val META_TABLE_UNREAD = "table.unread"

const val META_TABLE_CHROME = "table.chrome"

const val META_TABLE_FLAGGED = "table.flagged"

fun scopeLabel(scope: DocScope): String = when (scope) {
    DocScope.FULL -> "документ целиком"
    DocScope.VIEWPORT -> "только то, что в кадре"
    DocScope.CROPPED -> "часть документа обрезана"
}

fun headerLabel(headerRows: Int): String = if (headerRows <= 0) "нет" else headerRows.toString()
