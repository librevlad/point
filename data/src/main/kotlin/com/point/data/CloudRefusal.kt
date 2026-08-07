package com.point.data

internal fun summariseCloudErrors(errors: List<String>): String = when {
    errors.isNotEmpty() && errors.all { it.isNetworkError() } ->
        "Облачное чтение недоступно — нет подключения к интернету"
    errors.isNotEmpty() && errors.all { it.isQuotaError() } ->
        "Бесплатные лимиты чтения исчерпаны — вернитесь позже, платить не идём"
    else -> "Облачное чтение не удалось — " +
        errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
}

internal fun String.isNetworkError(): Boolean = NETWORK_HINTS.any { contains(it, ignoreCase = true) }

internal fun String.isQuotaError(): Boolean = QUOTA_HINTS.any { contains(it, ignoreCase = true) }

private val NETWORK_HINTS = listOf(
    "resolve host", "No address associated", "Unable to resolve",
    "connection abort", "Network is unreachable", "Failed to connect",
    "timed out", "timeout",
)

internal const val FREE_LIMIT_SPENT = "бесплатный лимит исчерпан"

private val QUOTA_HINTS = listOf("(402)", "(429)", "HTTP 402", "HTTP 429", FREE_LIMIT_SPENT)
