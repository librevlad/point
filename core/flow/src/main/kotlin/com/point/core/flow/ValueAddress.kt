package com.point.core.flow

sealed interface AtomAddress {

    data class ByRegion(val region: Box) : AtomAddress

    data class ByIds(val ids: List<String>) : AtomAddress
}

data class ResolvedValue(
    val atoms: List<Atom>,
    val text: String,
    val droppedIds: List<String> = emptyList(),
    val disjoint: Boolean = false,
)

fun AtomLayer.resolve(address: AtomAddress): ResolvedValue = when (address) {
    is AtomAddress.ByRegion -> {
        val hit = atomsIn(address.region)
        ResolvedValue(hit, textIn(address.region))
    }
    is AtomAddress.ByIds -> {
        val index = atoms.associateBy { it.id }
        val wanted = address.ids.distinct()
        val found = wanted.mapNotNull { index[it] }
        val ordered = readingOrder(found)
        ResolvedValue(
            atoms = ordered,
            text = ordered.joinToString(" ") { it.text },
            droppedIds = wanted.filter { it !in index },
            disjoint = !isConnected(ordered),
        )
    }
}

private fun isConnected(atoms: List<Atom>): Boolean {
    if (atoms.size <= 1) return true
    val visited = HashSet<Int>()
    val queue = ArrayDeque<Int>().apply { add(0) }
    visited += 0
    while (queue.isNotEmpty()) {
        val cur = atoms[queue.removeFirst()]
        atoms.forEachIndexed { i, other ->
            if (i !in visited && near(cur, other)) {
                visited += i
                queue.add(i)
            }
        }
    }
    return visited.size == atoms.size
}

private fun near(a: Atom, b: Atom): Boolean {
    val dx = a.box.centerX - b.box.centerX
    val dy = a.box.centerY - b.box.centerY
    val reach = maxOf(a.box.height, b.box.height) * NEIGHBOUR_HEIGHTS +
        (a.box.right - a.box.left + b.box.right - b.box.left) / 2f
    return dx * dx + dy * dy <= reach * reach
}

private const val NEIGHBOUR_HEIGHTS = 3f
