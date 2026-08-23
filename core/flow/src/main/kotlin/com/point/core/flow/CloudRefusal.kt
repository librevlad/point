package com.point.core.flow

/**
 * Вся цепочка исполнителей отказала — что сказать человеку (#1237).
 *
 * Сводка была написана заново в каждой цепочке, и одно положение человека — интернета нет —
 * описывалось пятью разными фразами: «Понять» говорил одно, «Распознать текст» другое,
 * «В Excel» третье, а расшифровка речи про сеть не говорила вовсе и показывала склейку
 * чужих отказов. Норма записана рядом, у [NO_NETWORK_TEXT]: описывать одну выключенную сеть
 * на двух экранах по-разному — значит рассказывать человеку две разные истории.
 *
 * Здесь меняется только глагол. Слова про сеть и про исчерпанное бесплатное — одни.
 *
 * @param whatFailed что именно не вышло, в неопределённой форме: «прочитать»,
 *   «расшифровать», «дочитать таблицу». Обязателен у каждого вызова: умолчание молча
 *   подсунуло бы чужой цепочке чужой глагол.
 */
fun summariseCloudErrors(errors: List<String>, whatFailed: String): String = when {
    errors.isNotEmpty() && errors.all { looksLikeNetworkFailure(it) } -> NO_NETWORK_TEXT
    errors.isNotEmpty() && errors.all { looksLikeQuotaFailure(it) } -> FREE_LIMITS_SPENT_TEXT
    else -> "Не удалось $whatFailed — " +
        errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
}
