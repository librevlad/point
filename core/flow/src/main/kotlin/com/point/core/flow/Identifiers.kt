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

internal fun looksLikeTrackToken(text: String): Boolean {
    val token = text.trim()
    return (SPLIT_SHAPED.matches(token) && token.count(Char::isDigit) in SHORT_TRACK_DIGITS..WAYBILL_DIGITS) ||
        s10CheckDigitValid(token) == true
}

const val META_ENTITY_TRACK = META_ENTITY_PREFIX + "track"

fun trackFacts(text: String): Map<String, String> {
    val hits = trackHits(text)
    val blocked = blockedTracks(text)
    if (hits.isEmpty() && blocked.isEmpty()) return emptyMap()
    return buildMap {
        hits.firstOrNull()?.let { first ->
            put(META_ENTITY_TRACK, first.value)

            put(META_ENTITY_TRACK + META_SOURCE_SUFFIX, Provenance.OCR.wire)
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
