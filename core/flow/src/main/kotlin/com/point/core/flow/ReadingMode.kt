package com.point.core.flow

enum class ReadingMode {

    PRINTED,

    HANDWRITTEN,

    UNKNOWN,
}

const val META_READING_MODE = "reading.mode"

fun readingModeOf(layer: AtomLayer?): ReadingMode = when {
    layer == null -> ReadingMode.UNKNOWN
    layer.atoms.none { it.text.isNotBlank() } -> ReadingMode.HANDWRITTEN

    weaklyRead(layer) -> ReadingMode.HANDWRITTEN
    else -> ReadingMode.PRINTED
}

fun readingModeOfFrame(layer: AtomLayer?, engineText: String): ReadingMode = when {
    layer != null -> readingModeOf(layer)
    engineText.isBlank() || looksLikeOcrGarbage(engineText) -> ReadingMode.HANDWRITTEN
    else -> ReadingMode.UNKNOWN
}

fun readingModeOf(metadata: Map<String, String>): ReadingMode =
    ReadingMode.entries.firstOrNull { it.name.equals(metadata[META_READING_MODE], ignoreCase = true) }
        ?: ReadingMode.UNKNOWN

fun printedGuarantees(mode: ReadingMode): Boolean = mode == ReadingMode.PRINTED
