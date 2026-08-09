package com.point.core.flow

/**
 * Транспортная ошибка провайдера — не слова для человека: «Software caused
 * connection abort» уходил на экран как исход «В Excel» (живая находка владельца,
 * 2026-08-09). Общий словарь обеих сторон: канал переводит такие ошибки в
 * человеческое «связь оборвалась», не теряя честности отказа.
 */
fun looksLikeNetworkFailure(error: String): Boolean =
    NETWORK_FAILURE_HINTS.any { error.contains(it, ignoreCase = true) }

fun looksLikeQuotaFailure(error: String): Boolean =
    QUOTA_FAILURE_HINTS.any { error.contains(it, ignoreCase = true) }

const val FREE_LIMIT_SPENT = "бесплатный лимит исчерпан"

private val NETWORK_FAILURE_HINTS = listOf(
    "resolve host", "No address associated", "Unable to resolve",
    "connection abort", "Network is unreachable", "Failed to connect",
    "timed out", "timeout", "Connection reset", "Broken pipe",
)

private val QUOTA_FAILURE_HINTS = listOf("(402)", "(429)", "HTTP 402", "HTTP 429", FREE_LIMIT_SPENT)
