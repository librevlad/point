package com.point.core.flow

fun summariseCloudErrors(errors: List<String>): String = when {
    errors.isNotEmpty() && errors.all { it.isNetworkError() } ->
        "Облачное чтение недоступно — нет подключения к интернету"
    errors.isNotEmpty() && errors.all { it.isQuotaError() } ->
        "Бесплатные лимиты чтения исчерпаны — вернитесь позже, платить не идём"
    else -> "Облачное чтение не удалось — " +
        errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
}

fun String.isNetworkError(): Boolean = looksLikeNetworkFailure(this)

fun String.isQuotaError(): Boolean = looksLikeQuotaFailure(this)
