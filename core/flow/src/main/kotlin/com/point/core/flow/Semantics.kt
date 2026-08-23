package com.point.core.flow

import com.point.core.model.Feature

const val META_SEMANTIC_PREFIX = "semantic."

const val META_SEMANTIC_TYPE = META_SEMANTIC_PREFIX + "type"

const val META_SEMANTIC_SUMMARY = META_SEMANTIC_PREFIX + "summary"

/**
 * Языковое правило сути — одно на все пути, что кладут знание под [META_SEMANTIC_SUMMARY]
 * (#1036, решение владельца 20.08.2026: «одно языковое правило на оба промпта»).
 *
 * Суть объекта ложится его подзаголовком, а Point говорит с человеком по-русски — и знание
 * об объекте тоже (#670, живой прогон 2026-08-09: «blue water meter in dirt» подзаголовком).
 * Правило жило текстом одного зрячего промпта: текстовый просил «на языке документа»,
 * голосовой — «на языке записи», и подзаголовок приходил то по-английски, то по-украински
 * (#1008 — тот же корень). Теперь фраза одна, а каждый промпт называет в ней своё.
 *
 * [what] — что именно пишется по-русски, словами самого промпта (`SUMMARY`, `СУТЬ`);
 * [source] — что при этом может быть на другом языке (надпись на снимке, текст, запись).
 * Дословное правило не трогает: номера, имена, адреса и слова записи читаются как есть.
 */
fun answerLanguageRule(what: String, source: String): String =
    "$what пиши по-русски, даже если $source на другом языке."

val SEMANTIC_TYPES: Map<String, Feature> = mapOf(
    "meeting" to Feature.IS_MEETING,
    "purchase" to Feature.IS_PURCHASE,
    "recipe" to Feature.IS_RECIPE,
    "job" to Feature.IS_JOB,
)
