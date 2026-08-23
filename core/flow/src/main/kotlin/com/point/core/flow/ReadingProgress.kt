package com.point.core.flow

/**
 * Курсор чтения «Понять» (#682/#683): длинный объект читается частями, и следующее
 * нажатие продолжает с того места, где остановилось прошлое, а не читает вслепую
 * начало заново.
 *
 * Это не прочтение и не знание об объекте, а отметка операции — сколько символов уже
 * отдано в разбор. Ключи refreshable (см. [REFRESHABLE_KNOWLEDGE]): новое значение
 * заменяет старое, а не спорит с ним, как обычное прочтение.
 */
const val META_READ_CHARS = "understand.read_chars"

const val META_READ_TOTAL_CHARS = "understand.total_chars"

fun readProgressOf(metadata: Map<String, String>): Int =
    metadata[META_READ_CHARS]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

/**
 * «Прочитано начало — N из M символов» (решение владельца #682/#683): честно называет,
 * сколько уже прочитано, когда объект прочитан не весь, и напоминает про дверь «читать
 * дальше» — повторное нажатие той же «Понять» (RFC §25: тот же виток Discovery над тем
 * же Graph, не отдельная кнопка).
 */
fun partialReadMessage(read: Int, total: Int): String =
    "Прочитано начало — ${grouped(read)} из ${grouped(total)} символов. " +
        "Нажмите «Понять» ещё раз — прочитаю дальше."

// U+00A0 (неразрывный пробел) — та же конвенция, что у core:ui grouped():
// большое число не переносится по строкам посреди себя. Одно на все итоги, где называется
// число знаков (#1023: «проверено начало — N из M символов»).
internal fun grouped(n: Int): String =
    n.toString().reversed().chunked(3).joinToString(" ").reversed()
