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
 *
 * [readText] — всё, что Point прочитал сам: гейт формы судит исправленное той же меркой, что
 * и найденное впервые (#666, #1032), и слово-подпись накладной ищет там же, на странице.
 */
fun parseFixes(answer: String, facts: List<FixableFact>, readText: String = ""): Map<String, String> {
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
        if (!factFits(fact.key, fixed, readText)) return@forEach
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

/**
 * Правка самого текста (#1023): у текстового объекта знание — это его текст, и первая ступень
 * проверяет именно его, а не сводку извлечённых значений. Прежде модели уходил один список
 * «1 = 17.08.2026», пять опечаток в тексте никто не смотрел, а итог звучал «Ошибок не нашлось».
 *
 * Протокол тот же по духу, что и у значений: модель возвращает только пары «было = стало», и
 * фрагмент, которого в тексте нет, не правится — придумать правку для несуществующего нельзя.
 *
 * [places] — в скольких местах правка легла: одно и то же слово с опечаткой повторяется, и
 * счёт в итоге называет места, а не пары.
 */
data class TextFix(val was: String, val now: String, val places: Int = 1)

/**
 * Текст после правок и сами правки: [fixes] — те, что легли (это и есть видимая дельта),
 * [missed] — те, что предложены, но в тексте целым фрагментом не нашлись. Вторые не молчат:
 * «предложено, но не легло» — исход операции, а не знание «ошибок нет» (Конституция §13).
 */
data class FixedText(val text: String, val fixes: List<TextFix>, val missed: List<TextFix>)

/**
 * Сколько текста уходит в модель за раз — столько же, сколько берёт разведка. Длинный текст
 * одним вопросом не проверить: запрос падает или провайдер видит только начало. Окно режет
 * запрос, а не сам текст: правка ложится поверх всего текста, и итог честно называет, какая
 * часть проверена.
 */
fun fixTextWindow(text: String): String = readWindowOf(text, 0, KNOWN_TEXT_LIMIT)

fun fixTextPrompt(text: String): String = buildString {
    append("Ниже текст. В нём бывают опечатки и описки: перепутанные, пропущенные или лишние ")
    append("буквы, слипшиеся или разорванные слова, мусор, вклинившийся в слово.\n\n")
    append(text)
    append("\n\n")
    append("Верни ТОЛЬКО строки вида «было = стало», по одной на строку, и только там, где ")
    append("действительно нужна правка. «Было» — фрагмент ровно так, как он стоит в тексте ")
    append("(целое слово или несколько слов подряд), «стало» — тот же фрагмент исправленным. ")
    append("Смысл, стиль и порядок слов не меняй и ничего не додумывай. ")
    append("Сами числа, даты и суммы не трогай — только слово, в которое вклинился мусор. ")
    append("Если править нечего — ответь ровно ").append(FIX_NOTHING).append('.')
}

/**
 * Разбор ответа и правка текста в один проход: правка ложится, только если «было» стоит в
 * тексте целым фрагментом — внутри другого слова оно не трогается («Эт» не правит «Этот»).
 * Пустые строки и мусор молчат; пара, чьего «было» в тексте нет, уходит в [FixedText.missed].
 */
fun fixText(text: String, answer: String): FixedText {
    var current = text
    val landed = ArrayList<TextFix>()
    val missed = ArrayList<TextFix>()
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val (was, now) = unquoted(line.substring(0, eq).trim(), line.substring(eq + 1).trim())
        if (was.isEmpty() || now.isEmpty() || was == now) return@forEach
        val (replaced, places) = replaceWhole(current, was, now)
        if (places == 0) {
            missed += TextFix(was, now, places = 0)
            return@forEach
        }
        current = replaced
        landed += TextFix(was, now, places)
    }
    return FixedText(current, landed, missed)
}

/**
 * Знание, вычитанное из текста, следует за правкой текста: значение — цитата из него, и
 * опечатка в тексте — та же опечатка в значении. Иначе у текстового объекта значения теряли бы
 * единственный путь починки: вырезанный из прочитанного снимка фрагмент несёт ошибки чтения и
 * в тексте, и в вычитанных из него значениях. Ложится теми же парами, теми же границами слова
 * и через ту же проверку формы, что и правка значений напрямую.
 */
fun fixesForFacts(facts: List<FixableFact>, fixes: List<TextFix>): Map<String, String> =
    facts.mapNotNull { fact ->
        val fixed = fixes.fold(fact.value) { value, fix -> replaceWhole(value, fix.was, fix.now).first }
        fixed.takeIf { normConsensus(it) != normConsensus(fact.value) && factFits(fact.key, it) }
            ?.let { fact.key to it }
    }.toMap()

/**
 * Итог с видимой дельтой: что было и что стало — человек видит правку, а не только счёт.
 * Счёт — по местам, где правка легла; перечисление не длиннее [DELTA_CHARS], остальное —
 * числом. Проверено не всё ([checked] < [total]) — сказано, какая часть, без вердикта об
 * остальном.
 */
fun fixedTextMessage(fixed: FixedText, checked: Int, total: Int): String {
    val scope = if (checked < total) "проверено начало — ${grouped(checked)} из ${grouped(total)} символов" else null
    if (fixed.fixes.isEmpty()) {
        return if (scope == null) "Ошибок не нашлось — текст оставлен как был"
        else "В проверенной части ошибок не нашлось ($scope) — текст оставлен как был"
    }
    return buildString {
        append("Исправлено: ").append(fixed.fixes.sumOf { it.places }).append(" — ").append(delta(fixed.fixes))
        if (fixed.missed.isNotEmpty()) append("; ещё ").append(fixed.missed.size).append(" применить не удалось")
        if (scope != null) append(" (").append(scope).append(')')
    }
}

/**
 * Правки предложены, но ни одна не легла: «было» процитировано не так, как стоит в тексте.
 * Это срыв операции, а не знание «ошибок нет» — повторить можно.
 */
const val FIX_TEXT_NOT_APPLIED = "Проверил текст, но применить правки не удалось — текст оставлен как был"

/** Сколько знаков перечисления правок показывается; дальше — «и ещё N». */
private const val DELTA_CHARS = 120

private fun delta(fixes: List<TextFix>): String {
    val shown = ArrayList<String>()
    var length = 0
    for (fix in fixes) {
        val piece = "«${fix.was}» → «${fix.now}»" + if (fix.places > 1) " ×${fix.places}" else ""
        if (shown.isNotEmpty() && length + piece.length > DELTA_CHARS) break
        shown += piece
        length += piece.length + 2
    }
    val rest = fixes.size - shown.size
    return shown.joinToString(", ") + if (rest > 0) " и ещё $rest" else ""
}

/**
 * Кавычки из образца в запросе («было = стало»), а не из текста: модель охотно повторяет их —
 * вокруг каждой стороны или вокруг всей строки, — и без этого все правки отбрасывались бы разом.
 * Фрагмент, который сам стоит в тексте в кавычках, от этого не страдает: правка ложится внутрь.
 */
private fun unquoted(was: String, now: String): Pair<String, String> = when {
    wrapped(was) || wrapped(now) -> stripped(was) to stripped(now)
    was.firstOrNull()?.let { it in OPENING_QUOTES } == true && now.lastOrNull()?.let { it in CLOSING_QUOTES } == true ->
        was.drop(1).trim() to now.dropLast(1).trim()
    else -> was to now
}

private fun wrapped(piece: String) =
    piece.length >= 2 && piece.first() in OPENING_QUOTES && piece.last() in CLOSING_QUOTES

private fun stripped(piece: String) = if (wrapped(piece)) piece.substring(1, piece.length - 1).trim() else piece

private const val OPENING_QUOTES = "«\"'“„"
private const val CLOSING_QUOTES = "»\"'”“"

/**
 * Замена всех вхождений [was] целиком: по краям фрагмента не должно продолжаться слово.
 * Возвращает текст и число мест, где замена легла.
 */
private fun replaceWhole(text: String, was: String, now: String): Pair<String, Int> {
    val out = StringBuilder()
    var from = 0
    var places = 0
    while (true) {
        val at = text.indexOf(was, from)
        if (at < 0) break
        val end = at + was.length
        val startsWord = was.first().isLetterOrDigit() && at > 0 && text[at - 1].isLetterOrDigit()
        val endsWord = was.last().isLetterOrDigit() && end < text.length && text[end].isLetterOrDigit()
        if (startsWord || endsWord) {
            out.append(text, from, at + 1)
            from = at + 1
            continue
        }
        out.append(text, from, at).append(now)
        from = end
        places++
    }
    return out.append(text, from, text.length).toString() to places
}
