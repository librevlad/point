package com.point.core.flow

data class LayoutElement(val id: String, val text: String)

fun layoutOf(text: String, limit: Int = MAX_LAYOUT_ELEMENTS): List<LayoutElement> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(limit)
        .mapIndexed { i, line -> LayoutElement("P${i + 1}", line.take(MAX_ELEMENT_CHARS)) }
        .toList()

const val MAX_LAYOUT_ELEMENTS = 120

private const val MAX_ELEMENT_CHARS = 300
