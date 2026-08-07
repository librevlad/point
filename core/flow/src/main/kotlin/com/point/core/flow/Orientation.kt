package com.point.core.flow

fun readingScore(layer: AtomLayer): Double = layer.atoms.sumOf { atom ->
    val text = atom.text.trim()
    val readable = text.count(Char::isLetterOrDigit)
    if (text.length < MIN_WORD_CHARS || readable * 2 < text.length) {
        0.0
    } else {
        atom.confidence.toDouble() * readable
    }
}

private const val MIN_WORD_CHARS = 2

const val MIN_ORIENTATION_GAIN = 1.3

val ORIENTATION_ANGLES = listOf(90, 180, 270)

fun looksMisoriented(layer: AtomLayer): Boolean {
    val text = layer.text
    return text.isBlank() || looksLikeOcrGarbage(text) || readingScore(layer) < WEAK_READING_SCORE
}

private const val WEAK_READING_SCORE = 25.0

fun bestOrientation(base: AtomLayer, rotated: Map<Int, AtomLayer>): Int {
    val baseScore = readingScore(base)
    val best = rotated.maxByOrNull { readingScore(it.value) } ?: return 0
    val bestScore = readingScore(best.value)
    val gain = if (baseScore <= 0.0) bestScore else bestScore / baseScore
    return if (bestScore > 0.0 && gain >= MIN_ORIENTATION_GAIN) best.key else 0
}
