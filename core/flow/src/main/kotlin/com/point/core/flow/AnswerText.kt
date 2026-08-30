package com.point.core.flow

/**
 * Ответ модели без того, что человеку не адресовано (#1320).
 *
 * Место одно — то, где ответ приходит: «Понять», «В Excel», Word+, чат и «Исправить ошибки»
 * читают уже очищенный ответ, и своей заплатки у каждого действия не заводится.
 *
 * Живой случай 26.08.2026: думающая модель написала ход мысли вслух, Point счёл ответом всё
 * подряд — и рассуждение уехало человеку строками в лист Excel.
 */
fun answerOnly(answer: String): String {
    val text = withoutPreamble(withoutReasoning(answer))

    // Кроме рассуждения, модель не сказала ничего: ответа нет. Это отказ обращения, а не
    // ответ — пусть цепочка идёт к следующему сервису, а не кладёт человеку чужие мысли.
    if (text.isBlank()) error(ONLY_REASONING)
    return text
}

/** Ответом было одно рассуждение вслух — отвечать оказалось нечем (#1320). */
const val ONLY_REASONING = "модель ответила рассуждением без ответа"

/**
 * Рассуждение вслух — не ответ (#1320).
 *
 * Думающие модели (gpt-oss-20b/120b, qwen3.6) пишут ход мысли перед ответом и отбивают его
 * тегом. Закрытый блок снимается там, где стоит. Оборванный на полуслове уносит и всё, что
 * за ним: ответа за ним уже не будет — обычно на нём кончился ответ целиком. Бывает и
 * наоборот: сервис снял открывающий тег и оставил закрывающий — тогда рассуждение всё, что
 * стоит до него.
 */
private fun withoutReasoning(answer: String): String {
    var text = answer.trim()
    for (tag in REASONING_TAGS) {
        val open = "<$tag>"
        val close = "</$tag>"
        while (true) {
            val from = text.indexOf(open, ignoreCase = true)
            if (from < 0) break
            val to = text.indexOf(close, from, ignoreCase = true)
            if (to < 0) {
                text = text.take(from)
                break
            }
            text = text.removeRange(from, to + close.length)
        }
        val stray = text.lastIndexOf(close, ignoreCase = true)
        if (stray >= 0) text = text.substring(stray + close.length)
        text = text.trim()
    }
    return text
}

private val REASONING_TAGS = listOf("think", "thinking", "reasoning")

private fun withoutPreamble(answer: String): String {
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
