package com.point.core.flow

fun agoLabel(millisAgo: Long): String {
    if (millisAgo < 0) return "только что"
    val minutes = millisAgo / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
        hours < 24 -> "$hours ${plural(hours, "час", "часа", "часов")} назад"
        days == 1L -> "вчера"
        days < 7 -> "$days ${plural(days, "день", "дня", "дней")} назад"
        days < 31 -> "${days / 7} ${plural(days / 7, "неделю", "недели", "недель")} назад"
        days < 365 -> "${days / 30} ${plural(days / 30, "месяц", "месяца", "месяцев")} назад"
        else -> "давно"
    }
}

fun stampLabel(epochMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), zone)
    val hh = t.hour.toString().padStart(2, '0')
    val mm = t.minute.toString().padStart(2, '0')
    return "${t.dayOfMonth} ${MONTHS[t.monthValue - 1]} $hh:$mm"
}

private val MONTHS = listOf(
    "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
)

internal fun plural(n: Long, one: String, few: String, many: String): String {
    val mod100 = n % 100
    if (mod100 in 11..14) return many
    return when (n % 10) {
        1L -> one
        2L, 3L, 4L -> few
        else -> many
    }
}
