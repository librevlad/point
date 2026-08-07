package com.point.core.flow

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top

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

    val text: String
        get() = readerText
            ?: lines(atoms).joinToString("\n") { line -> line.joinToString(" ") { it.text } }

    fun lines(subset: List<Atom> = atoms): List<List<Atom>> {
        val placed = subset.map { it to (transform?.toUpright(it.box) ?: it.box) }
        val lines = mutableListOf<MutableList<Pair<Atom, Box>>>()
        placed.sortedBy { (_, box) -> box.centerY }.forEach { entry ->
            val (_, box) = entry
            val line = lines.lastOrNull()
            val sameLine = line != null &&
                kotlin.math.abs(line.last().second.centerY - box.centerY) <= box.height / 2f
            if (sameLine) line.add(entry) else lines.add(mutableListOf(entry))
        }
        return lines.map { line -> line.sortedBy { (_, box) -> box.left }.map { (atom, _) -> atom } }
    }
}
