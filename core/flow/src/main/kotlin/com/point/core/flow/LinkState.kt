package com.point.core.flow

import com.point.core.model.tr
import com.point.core.model.trPlural

sealed interface LinkState {

    data class Live(val agoMillis: Long) : LinkState

    data class Silent(val agoMillis: Long) : LinkState

    data object Checking : LinkState

    data object Never : LinkState

    /** Устройство знакомо, но эта сессия его ещё не слышала: «ждёт связи», а не молчание. */
    data object Waiting : LinkState
}

const val LINK_SILENCE_AFTER_MS = 3 * 60 * 1000L

fun linkStateOf(
    lastContactAt: Long?,
    now: Long,
    probing: Boolean = false,
    silenceAfterMs: Long = LINK_SILENCE_AFTER_MS,
    knownButUnheard: Boolean = false,
): LinkState {
    val settled = settledLink(lastContactAt, now, silenceAfterMs, knownButUnheard)
    return if (probing && settled !is LinkState.Live) LinkState.Checking else settled
}

private fun settledLink(
    lastContactAt: Long?,
    now: Long,
    silenceAfterMs: Long,
    knownButUnheard: Boolean,
): LinkState {
    if (lastContactAt == null) return if (knownButUnheard) LinkState.Waiting else LinkState.Never
    val ago = (now - lastContactAt).coerceAtLeast(0)
    if (ago >= silenceAfterMs) return LinkState.Silent(ago)
    return LinkState.Live(ago)
}

/**
 * Подпись состояния связи со вторым устройством. Показывает её сегодня одно место — строка
 * «Телефон · …» в окне на компьютере; в круге устройств на обеих поверхностях время последней
 * связи подписывает [deviceLine] своими словами.
 *
 * Давно не слышали — это факт, а не диагноз (#1114). «Не отвечает · молчит 2 часа» читалось как
 * поломка, хотя телефон просто лежал с погашенным экраном или вышел из сети. Известно здесь
 * ровно одно — когда его слышали в последний раз, — это и сказано: «был на связи 2 часа назад».
 */
fun linkLabel(state: LinkState): String = when (state) {
    is LinkState.Live -> tr("на связи")
    is LinkState.Silent -> tr("был на связи {0} назад", minutesWord(state.agoMillis))
    LinkState.Checking -> tr("проверяю связь…")
    LinkState.Never -> tr("ещё не связывались")
    LinkState.Waiting -> tr("ждёт связи")
}

private fun minutesWord(agoMillis: Long): String {
    val minutes = (agoMillis / 60_000).toInt()
    if (minutes < 1) return tr("меньше минуты")
    if (minutes >= 60) {
        val hours = minutes / 60
        return "$hours ${trPlural(hours, "час", "часа", "часов")}"
    }
    return "$minutes ${trPlural(minutes, "минуту", "минуты", "минут")}"
}
