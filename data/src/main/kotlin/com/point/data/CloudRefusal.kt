package com.point.data

/**
 * Одна человеческая строка вместо стены ошибок от каждого читателя.
 *
 * Общая для всех облачных цепочек (#280): у человека, которому не прочитали страницу, ровно один
 * вопрос — «почему», и ответ «нет сети» или «кончилось бесплатное» отвечает на него, а список из
 * четырёх строк с именами сервисов — нет.
 *
 * Живёт отдельно, потому что цепочек уже две (слой атомов и внешний глаз), а причина у отказа одна.
 * Разъехавшись, они начали бы объяснять одно и то же разными словами — и это была бы вторая правда
 * там, где правда одна.
 */
internal fun summariseCloudErrors(errors: List<String>): String = when {
    errors.isNotEmpty() && errors.all { it.isNetworkError() } ->
        "Облачное чтение недоступно — нет подключения к интернету"
    errors.isNotEmpty() && errors.all { it.isQuotaError() } ->
        "Бесплатные лимиты чтения исчерпаны — вернитесь позже, платить не идём"
    else -> "Облачное чтение не удалось — " +
        errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
}

internal fun String.isNetworkError(): Boolean = NETWORK_HINTS.any { contains(it, ignoreCase = true) }

/** 402 «нужна карта» и 429 «кончился лимит» — законный ответ, по которому идут дальше, а не в кассу. */
internal fun String.isQuotaError(): Boolean = QUOTA_HINTS.any { contains(it, ignoreCase = true) }

private val NETWORK_HINTS = listOf(
    "resolve host", "No address associated", "Unable to resolve",
    "connection abort", "Network is unreachable", "Failed to connect",
    "timed out", "timeout",
)

/**
 * «Бесплатное кончилось», сказанное словами, — та же марка и в отказе, и в признаке.
 *
 * Узнавание квоты держалось на коде, оставленном в тексте («(429)», «HTTP 429»). Как только отказ
 * перестаёт носить код — а AI-цепочка перестала, потому что код читал человек под своим объектом
 * (#452), — искать в ней стало нечего, и ветка «вернитесь позже» молча выключилась бы. Одна
 * константа на оба конца оставляет узнавание строгим: переписать фразу, не заметив признака,
 * больше нельзя.
 */
internal const val FREE_LIMIT_SPENT = "бесплатный лимит исчерпан"

private val QUOTA_HINTS = listOf("(402)", "(429)", "HTTP 402", "HTTP 429", FREE_LIMIT_SPENT)
