package com.point.core.flow

import com.point.core.model.tr
import com.point.core.model.trPlural

fun agoLabel(millisAgo: Long): String {
    if (millisAgo < 0) return tr("только что")
    val minutes = millisAgo / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> tr("только что")
        minutes < 60 -> tr("{0} {1} назад", minutes, trPlural(minutes, "минуту", "минуты", "минут"))
        hours < 24 -> tr("{0} {1} назад", hours, trPlural(hours, "час", "часа", "часов"))
        days == 1L -> tr("вчера")
        days < 7 -> tr("{0} {1} назад", days, trPlural(days, "день", "дня", "дней"))
        days < 31 -> tr("{0} {1} назад", days / 7, trPlural(days / 7, "неделю", "недели", "недель"))
        days < 365 -> tr("{0} {1} назад", days / 30, trPlural(days / 30, "месяц", "месяца", "месяцев"))
        else -> tr("давно")
    }
}

fun stampLabel(epochMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), zone)
    val hh = t.hour.toString().padStart(2, '0')
    val mm = t.minute.toString().padStart(2, '0')
    return "${t.dayOfMonth} ${MONTHS[t.monthValue - 1]} $hh:$mm"
}

/** Короткие русские месяцы в стиле продукта — «17 авг», «3 мая». Общие для подписей времени. */
internal val MONTHS = listOf(
    tr("янв"), tr("фев"), tr("мар"), tr("апр"), tr("мая"), tr("июн"), tr("июл"), tr("авг"), tr("сен"), tr("окт"), tr("ноя"), tr("дек"),
)
