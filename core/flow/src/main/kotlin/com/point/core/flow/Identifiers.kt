package com.point.core.flow

import com.point.core.model.Provenance

internal enum class TrackForm {

    NOVA_POSHTA,

    SPLIT,

    MARKED,

    S10,
}

internal data class TrackHit(val value: String, val form: TrackForm, val at: IntRange)

internal fun trackHits(text: String): List<TrackHit> {
    val hits = mutableListOf<TrackHit>()
    DIGIT_RUN.findAll(text).forEach { m ->
        val digits = m.value.count(Char::isDigit)
        val form = when {
            digits == WAYBILL_DIGITS -> TrackForm.NOVA_POSHTA
            digits == SHORT_TRACK_DIGITS && markerNear(text, m.range) -> TrackForm.MARKED
            else -> return@forEach
        }
        hits += TrackHit(m.value.replace(MULTI_SPACE, " ").trim(), form, m.range)
    }
    SPLIT_SHAPED.findAll(text)
        .filter { it.value.count(Char::isDigit) in SHORT_TRACK_DIGITS..WAYBILL_DIGITS }
        .forEach { hits += TrackHit(it.value.trim(), TrackForm.SPLIT, it.range) }
    S10_IN_TEXT.findAll(text)
        .filter { s10CheckDigitValid(it.value) == true }
        .forEach { hits += TrackHit(it.value.trim(), TrackForm.S10, it.range) }

    val kept = mutableListOf<TrackHit>()
    hits.sortedBy { it.at.first }.forEach { hit ->
        val overlaps = kept.any { it.at.first <= hit.at.last && hit.at.first <= it.at.last }
        if (!overlaps) kept += hit
    }
    return kept.distinctBy { trackKey(it.value) }
}

fun waybillNumbers(text: String): List<String> = trackHits(text).map { it.value }

internal const val WAYBILL_DIGITS = 14

internal const val SHORT_TRACK_DIGITS = 13

private val DIGIT_RUN = Regex("""(?<!\d)\d[\d ]{11,20}\d(?!\d)""")

private val SPLIT_SHAPED = Regex("""(?<![\d/])(?<!/ )(\d{4,10}) ?/ ?(\d{4,10})(?![\d/])(?! ?/)""")

private val S10_IN_TEXT = Regex("""(?<![\p{L}\d])[A-Za-z]{2} ?\d{9} ?[A-Za-z]{2}(?![\p{L}\d])""")

private val MULTI_SPACE = Regex(""" {2,}""")

private fun trackKey(value: String): String = value.filter(Char::isLetterOrDigit).uppercase()

internal fun markerNear(
    text: String,
    at: IntRange,
    isMarker: (String) -> Boolean = ::looksLikeTrackMarker,
): Boolean {
    val lineStart = text.lastIndexOf('\n', at.first).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', at.last).let { if (it < 0) text.length else it }
    val sameLineBefore = text.substring(lineStart, at.first).tokens()
    val sameLineAfter = text.substring(minOf(at.last + 1, lineEnd), lineEnd).tokens()
    val before = sameLineBefore.ifEmpty { tailAbove(text, lineStart) }.takeLast(MARKER_TOKENS_BEFORE)
    val after = sameLineAfter.ifEmpty { headBelow(text, lineEnd) }.take(MARKER_TOKENS_AFTER)
    return (before + after).any(isMarker)
}

private fun tailAbove(text: String, lineStart: Int): List<String> =
    text.take((lineStart - 1).coerceAtLeast(0)).lineSequence()
        .map { it.tokens() }.lastOrNull { it.isNotEmpty() }.orEmpty()

private fun headBelow(text: String, lineEnd: Int): List<String> =
    text.drop(minOf(lineEnd + 1, text.length)).lineSequence()
        .map { it.tokens() }.firstOrNull { it.isNotEmpty() }.orEmpty()

private fun String.tokens(): List<String> = split(WHITESPACE).filter { it.any(Char::isLetterOrDigit) }

private val WHITESPACE = Regex("""\s+""")

private const val MARKER_TOKENS_BEFORE = 2

private const val MARKER_TOKENS_AFTER = 1

internal fun looksLikeTrackMarker(word: String): Boolean {
    val folded = foldOcr(word).trim { !it.isLetterOrDigit() }
    return folded.isNotEmpty() && FOLDED_TRACK_MARKERS.any { it in folded }
}

private val TRACK_MARKER_STEMS = listOf(
    "ттн", "накладн", "відправлен", "отправлен", "трек", "waybill", "tracking",
)

private val FOLDED_TRACK_MARKERS = TRACK_MARKER_STEMS.map(::foldOcr)

/**
 * Форма накладной — улика для атома страницы (RuleEvidence).
 *
 * Мерка нарочно уже, чем у [looksLikeTrack]: атом метится уликой «это трек», только когда
 * он целиком и есть номер — две группы через дробь или S10 с сошедшейся контрольной. Улика
 * питает заземление значений, и расширять её вместе с гейтом ключа нельзя: голый прогон
 * цифр в ячейке и номер, найденный подстрокой, уликой не были и не становятся (#1032).
 */
internal fun looksLikeTrackToken(text: String): Boolean {
    val token = text.trim()
    return (SPLIT_SHAPED.matches(token) && token.count(Char::isDigit) in SHORT_TRACK_DIGITS..WAYBILL_DIGITS) ||
        s10CheckDigitValid(token) == true
}

/**
 * Накладная — по форме перевозчика или по слову рядом (#1032, решение владельца).
 *
 * У ключа `entity.track` было две мерки. Правило-читатель ([trackHits]) брало 13 цифр только
 * с подписью рядом, а значение от модели проходило по одной длине: номер 8806923102858 из
 * машиночитаемой зоны удостоверения местный путь не брал, модельный брал — и человеку на
 * удостоверении личности показывали «Накладную» с предложением отследить. Мерка одна на
 * ключ: форма перевозчика (14 цифр «Новой пошты», две группы через дробь, S10 — его
 * контрольную цифру судит суд кандидатов) либо слово-подпись рядом в прочитанном тексте.
 * Без того и другого число — не накладная.
 */
fun looksLikeTrack(value: String, text: String = ""): Boolean {
    val token = value.trim()
    if (s10CheckDigitValid(token) != null) return true
    if (trackHits(token).isNotEmpty()) return true
    val key = trackKey(token)
    return key.isNotEmpty() && trackHits(text).any { trackKey(it.value) == key }
}

/**
 * Стоит ли число на странице со словом-подписью рядом (#1032).
 *
 * Это то, что страница даёт **месту**, а не самому числу: слово рядом одинаково верно для
 * любого прочтения этого места, и правка его наследует ([fixFits]). Форма перевозчика —
 * свойство самого числа: 14 цифр накладная по себе, но с исправленными 13 цифрами этой
 * формой не поделиться.
 */
internal fun markedTrackOnPage(value: String, text: String): Boolean {
    val key = trackKey(value.trim())
    return key.isNotEmpty() && trackHits(text).any { trackKey(it.value) == key && markerNear(text, it.at) }
}

const val META_ENTITY_SERIAL = META_ENTITY_PREFIX + "serial"

/**
 * Серия «буквы+цифры» — идентификатор, в который можно войти (#1066, #991).
 *
 * Госномер BH9249MT, серия паспорта, номер удостоверения — самый уверенный атом кадра
 * оставался просто текстом: ни у одного правила не было формы для смеси букв и цифр
 * одним токеном. Отдельного типа «госномер» не заводится (CLAUDE.md): это KIND_IDENTIFIER,
 * как накладная и квитанция.
 *
 * Форма нарочно узкая, чтобы не съедать чужое: один токен 6–10 знаков, только заглавные
 * буквы и цифры, букв не меньше двух и цифр не меньше трёх, не целиком цифры (это земля
 * трека/штрихкода/телефона) и не машинный префикс имени файла (IMG_1234, DSC0042).
 */
fun serialFacts(text: String, source: Provenance = Provenance.OCR): Map<String, String> {
    val hits = SERIAL_TOKEN.findAll(text)
        .map { it.value }
        .filter { token ->
            token.count(Char::isDigit) >= 3 &&
                token.count(Char::isLetter) >= 2 &&
                MACHINE_NAME_PREFIXES.none { token.startsWith(it) }
        }
        .distinct()
        .toList()
    if (hits.isEmpty()) return emptyMap()
    return buildMap {
        put(META_ENTITY_SERIAL, hits.first())
        put(META_ENTITY_SERIAL + META_SOURCE_SUFFIX, source.wire)
        if (hits.size > 1) put(META_ENTITY_SERIAL + META_MORE_SUFFIX, altValue(hits.drop(1)))
    }
}

/** Токен серии: слово из заглавных букв и цифр, со всех сторон — граница слова. */
private val SERIAL_TOKEN = Regex("""(?<![\p{L}\p{N}])[A-ZА-ЯІЇЄ0-9]{6,10}(?![\p{L}\p{N}])""")

private val MACHINE_NAME_PREFIXES = listOf("IMG", "DSC", "PXL", "SCR", "DCIM", "VID")

const val META_ENTITY_TRACK = META_ENTITY_PREFIX + "track"

fun trackFacts(text: String, source: Provenance = Provenance.OCR): Map<String, String> {
    val hits = trackHits(text)
    val blocked = blockedTracks(text)
    if (hits.isEmpty() && blocked.isEmpty()) return emptyMap()
    return buildMap {
        hits.firstOrNull()?.let { first ->
            put(META_ENTITY_TRACK, first.value)

            put(META_ENTITY_TRACK + META_SOURCE_SUFFIX, source.wire)
            put(
                META_ENTITY_TRACK + META_EVIDENCE_SUFFIX,
                formEvidence(META_ENTITY_TRACK, first.value)
                    .joinToString(",") { it.name.lowercase() },
            )
            if (hits.size > 1) {
                put(META_ENTITY_TRACK + META_MORE_SUFFIX, altValue(hits.map { it.value }))
            }
        }
        if (blocked.isNotEmpty()) put(META_ENTITY_TRACK + META_BLOCKED_SUFFIX, altValue(blocked))
    }
}

private fun blockedTracks(text: String): List<String> =
    S10_IN_TEXT.findAll(text)
        .filter { s10CheckDigitValid(it.value) == false && markerNear(text, it.range) }
        .map { it.value.trim() }
        .distinct()
        .toList()
