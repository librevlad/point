package com.point.core.flow

/**
 * Есть ли связь между телефоном и компьютером — и каким путём (#412).
 *
 * Владелец: «на телефоне не видно подключен ли пк и наоборот». До этого обе стороны молчали:
 * человек тапал «Напечатать на ПК», ничего не происходило, и понять, сломалось оно или связи нет,
 * было нельзя.
 *
 * Живёт в `:core:flow`, потому что обе стороны обязаны говорить об этом **одинаково**: если
 * телефон считает связь живой, а компьютер — потерянной, спорить будут они, а виноватым окажется
 * человек.
 */
enum class LinkPath {
    /** Прямое соединение по локальной сети — быстро и без облака. */
    LAN,

    /** Через релей: устройства в разных сетях (или роутер разводит их изоляцией клиентов). */
    RELAY,
}

sealed interface LinkState {
    /** Слышали недавно: [path] — каким путём, [agoMillis] — сколько назад. */
    data class Live(val path: LinkPath, val agoMillis: Long) : LinkState

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
 * Путь берётся из последнего контакта намеренно: он объясняет человеку скорость и то, почему в
 * чужой сети всё медленнее. Отсутствие пути при наличии контакта невозможно по построению — но
 * если такое случится, честнее показать молчание, чем выдумать путь.
 *
 * [probing] — «запрос к компьютеру сейчас в пути» (#451). Он перебивает ответ о прошлом, но
 * только когда прошлое молчит: свежий контакт уже отвечает на вопрос человека, и гасить «на
 * связи» ради секунды «проверяю» значило бы мигать вместо того, чтобы сообщать.
 */
fun linkStateOf(
    lastContactAt: Long?,
    path: LinkPath?,
    now: Long,
    probing: Boolean = false,
    silenceAfterMs: Long = LINK_SILENCE_AFTER_MS,
): LinkState {
    val settled = settledLink(lastContactAt, path, now, silenceAfterMs)
    return if (probing && settled !is LinkState.Live) LinkState.Checking else settled
}

private fun settledLink(lastContactAt: Long?, path: LinkPath?, now: Long, silenceAfterMs: Long): LinkState {
    if (lastContactAt == null) return LinkState.Never
    val ago = (now - lastContactAt).coerceAtLeast(0)
    if (ago >= silenceAfterMs || path == null) return LinkState.Silent(ago)
    return LinkState.Live(path, ago)
}

/**
 * Как это сказать человеку.
 *
 * Словами продукта, а не техники: «в этой сети» и «через интернет» вместо «LAN» и «relay» —
 * человеку важно, быстро ли и работает ли вдали от дома, а не название транспорта.
 */
fun linkLabel(state: LinkState): String = when (state) {
    is LinkState.Live -> when (state.path) {
        LinkPath.LAN -> "на связи · в этой сети"
        LinkPath.RELAY -> "на связи · через интернет"
    }
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
