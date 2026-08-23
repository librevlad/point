package com.point.core.flow

/**
 * Вся цепочка исполнителей отказала — что сказать человеку (#1237).
 *
 * Сводка была написана заново в каждой цепочке, и одно положение человека — интернета нет —
 * описывалось пятью разными фразами: «Понять» говорил одно, «Распознать текст» другое,
 * «В Excel» третье, а расшифровка речи про сеть не говорила вовсе и показывала склейку
 * чужих отказов. Норма записана рядом, у [NO_NETWORK_TEXT]: описывать одно и то же положение
 * на двух экранах по-разному — значит рассказывать человеку две разные истории.
 *
 * Что именно сказать про сеть, решает [cloudRefusalKind]: сводка видит только чужие строки и
 * потому говорит «связь оборвалась», а «интернета нет» остаётся тому, кто спросил телефон.
 *
 * Здесь меняется только глагол. Слова про сеть и про исчерпанное бесплатное — одни.
 *
 * @param whatFailed что именно не вышло, в неопределённой форме: «прочитать»,
 *   «расшифровать», «дочитать таблицу». Обязателен у каждого вызова: умолчание молча
 *   подсунуло бы чужой цепочке чужой глагол.
 */
fun summariseCloudErrors(errors: List<String>, whatFailed: String): String = when (cloudRefusalKind(errors)) {
    CloudRefusalKind.CONNECTION_LOST -> CONNECTION_LOST_TEXT
    CloudRefusalKind.FREE_SPENT -> FREE_LIMITS_SPENT_TEXT
    CloudRefusalKind.OTHER -> "Не удалось $whatFailed — " +
        errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
}

/**
 * Про что этот отказ цепочки (#1260).
 *
 * Приписки к отказу решаются той же веткой, какой выбраны слова: приписка «есть читатель
 * посильнее, задайте ключ» уместна везде, кроме оборвавшейся связи, — там ключ не решает
 * ничего. Пока ветку узнавали сравнением готовой строки, любой добавленный суффикс молча
 * гасил приписку, а новая ветка про неё не знала вовсе.
 */
enum class CloudRefusalKind { CONNECTION_LOST, FREE_SPENT, OTHER }

fun cloudRefusalKind(errors: List<String>): CloudRefusalKind = when {
    errors.isEmpty() -> CloudRefusalKind.OTHER
    errors.all { looksLikeNetworkFailure(it) } -> CloudRefusalKind.CONNECTION_LOST
    errors.all { looksLikeQuotaFailure(it) } -> CloudRefusalKind.FREE_SPENT
    else -> CloudRefusalKind.OTHER
}

/**
 * Отказ цепочки: человеку — сводка, журналу — чужой ответ дословно (#1236).
 *
 * Пока чужое тело ответа стояло прямо в тексте исключения, оно уезжало на баннер — и оно же
 * было единственным, что попадало в журнал обменов ([LoggingLlmClient]). Разрезано на два
 * канала: текст — наши слова, [serviceSaid] — то, что сервис ответил на самом деле. Второе
 * пишется только в журнал отладочного стенда и человеку не показывается никогда.
 */
class CloudChainRefusal(said: String, val serviceSaid: String) : IllegalStateException(said)

/** Что сервисы ответили дословно, если это сохранено. Наружу, к человеку, не идёт. */
fun serviceSaidIn(failure: Throwable): String? = when (failure) {
    is CloudChainRefusal -> failure.serviceSaid
    is AiServiceRefusal -> failure.serviceSaid
    else -> null
}?.takeIf { it.isNotBlank() }
