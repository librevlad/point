package com.point.data

import com.point.core.flow.FREE_LIMIT_SPENT as CORE_FREE_LIMIT_SPENT
import com.point.core.flow.looksLikeNetworkFailure
import com.point.core.flow.looksLikeQuotaFailure

internal fun summariseCloudErrors(errors: List<String>): String = when {
    errors.isNotEmpty() && errors.all { it.isNetworkError() } ->
        "Облачное чтение недоступно — нет подключения к интернету"
    errors.isNotEmpty() && errors.all { it.isQuotaError() } ->
        "Бесплатные лимиты чтения исчерпаны — вернитесь позже, платить не идём"
    else -> "Облачное чтение не удалось — " +
        errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
}

internal fun String.isNetworkError(): Boolean = looksLikeNetworkFailure(this)

internal fun String.isQuotaError(): Boolean = looksLikeQuotaFailure(this)

internal const val FREE_LIMIT_SPENT = CORE_FREE_LIMIT_SPENT
