package com.point.core.flow

enum class ReadingMode {

    PRINTED,

    HANDWRITTEN,

    UNKNOWN,
}

const val META_READING_MODE = "reading.mode"

/**
 * Как стоит текст на снимке: сколько градусов по часовой понадобилось чтению сверх метки
 * камеры (#1044). След работы, а не прочтение: по нему «Скан» ставит лист так, как его
 * прочли, а вопрос «что написано» им не закрывается.
 */
const val META_READ_TURN = "reading.turn"

/** Поворот листа, который назвало чтение, — четвертями по часовой; `null` — не называло. */
fun readQuarterTurns(metadata: Map<String, String>): Int? =
    metadata[META_READ_TURN]?.toIntOrNull()?.takeIf { it % 90 == 0 }?.let { Math.floorMod(it / 90, 4) }

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
