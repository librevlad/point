package com.point.core.flow

import com.point.core.model.Provenance

/**
 * «Исправить ошибки» (#666): всё знание объекта уходит в модель, она возвращает исправления
 * опечаток распознавания — «Паринкн» → «Паринкін», «ад! 01.12.2020» → «01.12.2020»,
 * «ZeHTpaJIbHa» → «Центральна».
 *
 * Протокол нумерованный, а не по именам полей: модель не может придумать поле, которого нет,
 * и не может переименовать существующее — номер возвращается ровно тот, что был выдан.
 */
data class FixableFact(val key: String, val value: String)

/**
 * Что вообще можно исправлять: значения знания, кроме подтверждённых человеком.
 *
 * Слово человека модель не трогает (ADR-0001 §8): он уже сказал, как правильно, и переспорить
 * его догадкой нельзя. Аннотации (`.alt`, `.src`, …) и служебные состояния — не значения.
 */
fun fixableFacts(metadata: Map<String, String>): List<FixableFact> =
    metadata.keys
        .filter { it.startsWith(META_ENTITY_PREFIX) || it.startsWith(META_GRAPH_ROLE_PREFIX) || it == META_SEMANTIC_SUMMARY }
        .filterNot { isAnnotationKey(it) || isStateKey(it) }
        .filterNot { provenanceOf(metadata, it) == Provenance.HUMAN }
        .sorted()
        .mapNotNull { key -> metadata[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { FixableFact(key, it) } }

/** Есть ли что исправлять: без знания дверь не показывается вовсе (решение владельца). */
fun hasFixableFacts(metadata: Map<String, String>): Boolean = fixableFacts(metadata).isNotEmpty()

const val FIX_NOTHING = "NONE"

fun fixPrompt(facts: List<FixableFact>, withObject: Boolean): String = buildString {
    append("Ниже значения, распознанные с документа. В них бывают ошибки распознавания: ")
    append("перепутанные похожие буквы, латиница вместо кириллицы, прилипший мусор, ")
    append("разорванные слова.\n\n")
    facts.forEachIndexed { i, f -> append(i + 1).append(" = ").append(f.value).append('\n') }
    append('\n')
    if (withObject) {
        append("Сверь значения с самим снимком, который приложен: он источник, а строки — ")
        append("его прочтение с ошибками.\n")
    }
    append("Верни ТОЛЬКО строки вида «номер = исправленное значение», по одной на строку, ")
    append("и только для тех номеров, где действительно нужна правка. ")
    append("Значение возвращай целиком, а не одну букву. ")
    append("Смысл не меняй и ничего не додумывай: если строка выглядит осмысленной — не трогай её. ")
    append("Цифры исправляй, только если видишь, что символ перепутан с похожим. ")
    append("Если править нечего — ответь ровно ").append(FIX_NOTHING).append('.')
}

/**
 * Разбор ответа: номер → исправленное значение. Чужие номера и мусор молчат.
 */
fun parseFixes(answer: String, facts: List<FixableFact>): Map<String, String> {
    val byIndex = facts.withIndex().associate { (i, f) -> i + 1 to f }
    val fixes = LinkedHashMap<String, String>()
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val n = line.substring(0, eq).trim().toIntOrNull() ?: return@forEach
        val fact = byIndex[n] ?: return@forEach
        val fixed = line.substring(eq + 1).trim()
        if (fixed.isEmpty() || normConsensus(fixed) == normConsensus(fact.value)) return@forEach
        if (!factFits(fact.key, fixed)) return@forEach
        fixes[fact.key] = fixed
    }
    return fixes
}

/**
 * «Авто + след» (решение владельца): исправленное становится главным значением, прежнее
 * остаётся рядом в «или» — человек видит, что было, и может вернуть.
 */
fun applyFixes(metadata: Map<String, String>, fixes: Map<String, String>): Map<String, String> {
    if (fixes.isEmpty()) return emptyMap()
    return buildMap {
        fixes.forEach { (key, fixed) ->
            val was = metadata[key]?.trim()
            put(key, fixed)
            put(key + META_SOURCE_SUFFIX, Provenance.MODEL.wire)
            val kept = (listOfNotNull(was) + alternativesOf(metadata, key))
                .distinct()
                .filter { normConsensus(it) != normConsensus(fixed) }
            if (kept.isNotEmpty()) put(key + META_ALT_SUFFIX, altValue(kept))
        }
    }
}

/** Итог одной строкой (решение владельца), без перечисления полей. */
fun fixedMessage(count: Int): String = when {
    count <= 0 -> "Ошибок не нашлось — знание оставлено как было"
    else -> "Исправлено: $count"
}
