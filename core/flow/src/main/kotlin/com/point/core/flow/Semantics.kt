package com.point.core.flow

import com.point.core.model.Feature

const val META_SEMANTIC_PREFIX = "semantic."

const val META_SEMANTIC_TYPE = META_SEMANTIC_PREFIX + "type"

const val META_SEMANTIC_SUMMARY = META_SEMANTIC_PREFIX + "summary"

/**
 * Объект уже понят (#1010).
 *
 * После «✓ Стало понятнее» главным подсвеченным действием оставалось «Понять» — с той же
 * подписью «найдёт суть, суммы, даты и контакты». Экран предлагал как лучший следующий шаг
 * ровно то, что только что сделано, хотя суть объекта уже лежит в графе.
 */
fun alreadyUnderstood(facts: Map<String, String>): Boolean =
    facts[META_SEMANTIC_SUMMARY]?.isNotBlank() == true

val SEMANTIC_TYPES: Map<String, Feature> = mapOf(
    "meeting" to Feature.IS_MEETING,
    "purchase" to Feature.IS_PURCHASE,
    "recipe" to Feature.IS_RECIPE,
    "job" to Feature.IS_JOB,
)
