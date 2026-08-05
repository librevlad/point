package com.point.core.flow

/**
 * Есть ли связь между телефоном и компьютером (#412).
 *
 * Владелец: «на телефоне не видно подключен ли пк и наоборот». До этого обе стороны молчали:
 * человек тапал «Напечатать на ПК», ничего не происходило, и понять, сломалось оно или связи нет,
 * было нельзя.
 *
 * Живёт в `:core:flow`, потому что обе стороны обязаны говорить об этом **одинаково**: если
 * телефон считает связь живой, а компьютер — потерянной, спорить будут они, а виноватым окажется
 * человек.
 *
 * Пути с #475 один, и вопроса «каким» больше нет: раньше состояние возило с собой [LinkPath] и
 * говорило «в этой сети» или «через интернет». Человеку это объясняло скорость, но теперь
 * объяснять нечего — выбора нет, и лишнее слово только просило бы его о чём-то подумать.
 */
sealed interface LinkState {
    /** Слышали недавно: [agoMillis] — сколько назад. */
    data class Live(val agoMillis: Long) : LinkState

    /** Слышали давно. Молчание названо, а не спрятано: «наверное, всё хорошо» — не ответ. */
    data class Silent(val agoMillis: Long) : LinkState

    /**
     * Спрашиваем прямо сейчас, ответа ещё нет (#451).
     *
     * Отдельное состояние, потому что «пока не знаю» — это не «не отвечает» и тем более не «ещё
     * не связывались»: оба последних утверждают о прошлом, а здесь идёт настоящее.
     */
    data object Checking : LinkState

    /** Не слышали ни разу — устройства ещё не связывались. */
    data object Never : LinkState
}

/** После какого молчания связь считается потерянной. */
const val LINK_SILENCE_AFTER_MS = 3 * 60 * 1000L

/**
 * Состояние связи по последнему контакту.
 *
 * [probing] — «запрос к компьютеру сейчас в пути» (#451). Он перебивает ответ о прошлом, но
 * только когда прошлое молчит: свежий контакт уже отвечает на вопрос человека, и гасить «на
 * связи» ради секунды «проверяю» значило бы мигать вместо того, чтобы сообщать.
 */
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

/** Как это сказать человеку — словами продукта, а не техники. */
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

/** Русский счёт: 1 минуту, 2 минуты, 5 минут — иначе строка читается как машинный вывод. */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    if (mod100 in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}
