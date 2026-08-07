package com.point.core.flow

sealed interface LinkState {

    data class Live(val agoMillis: Long) : LinkState

    data class Silent(val agoMillis: Long) : LinkState

    data object Checking : LinkState

    data object Never : LinkState
}

const val LINK_SILENCE_AFTER_MS = 3 * 60 * 1000L

fun linkStateOf(
    lastContactAt: Long?,
    now: Long,
    probing: Boolean = false,
    silenceAfterMs: Long = LINK_SILENCE_AFTER_MS,
): LinkState {
    val settled = settledLink(lastContactAt, now, silenceAfterMs)
    return if (probing && settled !is LinkState.Live) LinkState.Checking else settled
}

private fun settledLink(lastContactAt: Long?, now: Long, silenceAfterMs: Long): LinkState {
    if (lastContactAt == null) return LinkState.Never
    val ago = (now - lastContactAt).coerceAtLeast(0)
    if (ago >= silenceAfterMs) return LinkState.Silent(ago)
    return LinkState.Live(ago)
}

fun linkLabel(state: LinkState): String = when (state) {
    is LinkState.Live -> "на связи"
    is LinkState.Silent -> "не отвечает · молчит ${minutesWord(state.agoMillis)}"
    LinkState.Checking -> "проверяю связь…"
    LinkState.Never -> "ещё не связывались"
}

private fun minutesWord(agoMillis: Long): String {
    val minutes = (agoMillis / 60_000).toInt()
    if (minutes < 1) return "меньше минуты"
    if (minutes >= 60) {
        val hours = minutes / 60
        return "$hours ${plural(hours, "час", "часа", "часов")}"
    }
    return "$minutes ${plural(minutes, "минуту", "минуты", "минут")}"
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    if (mod100 in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
