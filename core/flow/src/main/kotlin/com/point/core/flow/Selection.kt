package com.point.core.flow

data class SnappedSelection(

    val region: Box,

    val atoms: List<Atom>,

    val text: String,

    val lineRegions: List<Box> = emptyList(),
) {
    val ids: List<String> get() = atoms.map { it.id }
}

fun AtomLayer.snapSelection(raw: Box, page: Int = 0): SnappedSelection {

    val frame = Box(
        minOf(raw.left, raw.right),
        minOf(raw.top, raw.bottom),
        maxOf(raw.left, raw.right),
        maxOf(raw.top, raw.bottom),
    )
    val hit = readingOrder(atoms.filter { it.page == page && it.box.intersects(frame) })
    if (hit.isEmpty()) return SnappedSelection(frame, emptyList(), "")
    return SnappedSelection(
        region = hit.map { it.box }.reduce(Box::union),
        atoms = hit,
        text = hit.joinToString(" ") { it.text },
        lineRegions = lines(hit).map { line -> line.map { it.box }.reduce(Box::union) },
    )
}

const val META_SELECTION_SOURCE = "selection.source"

const val META_SELECTION_IDS = "selection.ids"

const val META_SELECTION_REGION = "selection.region"

const val META_SELECTION_PAGE = "selection.page"
