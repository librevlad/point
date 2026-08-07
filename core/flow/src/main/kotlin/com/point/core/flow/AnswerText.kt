package com.point.core.flow

fun withoutPreamble(answer: String): String {
    val text = answer.trimStart()
    val firstBreak = text.indexOf('\n')
    if (firstBreak <= 0) return answer
    val first = text.substring(0, firstBreak).trim()
    val rest = text.substring(firstBreak + 1).trimStart()
    if (rest.isBlank()) return answer
    return if (isPreamble(first)) rest else answer
}

private fun isPreamble(line: String): Boolean {
    val bare = line.removePrefix("**").removeSuffix("**").removePrefix("#").trim()
    if (!bare.endsWith(":")) return false
    if (bare.length > MAX_PREAMBLE) return false
    val lower = bare.lowercase()
    return OPENERS.any { lower.startsWith(it) || lower.contains(" $it ") }
}

private val OPENERS = listOf(
    "вот", "ниже", "держите", "конечно", "разумеется", "готово",
    "here is", "here are", "sure", "certainly",
)

private const val MAX_PREAMBLE = 120
