package com.point.core.flow

/**
 * Чужое сообщение остаётся в журнале, человеку — свои слова (#686, #992).
 *
 * На свежем устройстве «Убрать фон» отвечало сообщением вендора по-английски и со значком
 * отказа: «Waiting for the subject segmentation optional module to be downloaded. Please wait.»
 * Крест говорит «не вышло», текст говорит «подождите», а что делать человеку — не сказано
 * ни там, ни там; заодно наружу вышел механизм — «optional module».
 *
 * Правило простое и одностороннее: сообщение на своём языке проходит как есть, чужое —
 * заменяется. Отдельно узнаётся один настоящий случай, у которого есть свои слова и своя
 * инструкция: возможность ещё готовится.
 */
fun ourWordsFor(raw: String?, fallback: String): String {
    val said = raw.orEmpty().trim()
    if (said.isEmpty()) return fallback
    if (said.any { it in 'а'..'я' || it in 'А'..'Я' }) return said

    val lower = said.lowercase()
    return if (STILL_DOWNLOADING.any { it in lower }) NOT_READY_YET else fallback
}

/**
 * Возможность на устройстве ещё качается. Это не отказ объекту и не поломка: подождать и
 * повторить — и есть инструкция (Product Constitution P9).
 */
const val NOT_READY_YET = "Эта возможность ещё готовится — Point докачивает её. Попробуйте через минуту"

private val STILL_DOWNLOADING = listOf(
    "optional module",
    "module to be downloaded",
    "is being downloaded",
    "waiting for the",
    "please wait",
    "not yet available",
)
