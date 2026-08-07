package com.point.core.flow

import com.point.core.model.Provenance

const val META_ENTITY_RECEIPT = META_ENTITY_PREFIX + "receipt"

private const val MIN_RECEIPT_CHARS = 6

fun receiptNumbers(text: String): List<String> =
    RECEIPT_SHAPED.findAll(text)
        .filter { receiptNumberShaped(it.value) }
        .filter { markerNear(text, it.range, ::looksLikeReceiptMarker) }
        .map { it.value.trim() }
        .distinctBy { it.filter(Char::isLetterOrDigit).uppercase() }
        .toList()

internal fun receiptNumberShaped(value: String): Boolean {
    val token = value.trim()
    return RECEIPT_SHAPED.matches(token) &&
        token.count(Char::isLetterOrDigit) >= MIN_RECEIPT_CHARS &&
        token.any(Char::isDigit)
}

fun receiptFacts(text: String): Map<String, String> {
    val numbers = receiptNumbers(text)
    val first = numbers.firstOrNull() ?: return emptyMap()
    return buildMap {
        put(META_ENTITY_RECEIPT, first)

        put(META_ENTITY_RECEIPT + META_SOURCE_SUFFIX, Provenance.OCR.wire)

        put(META_ENTITY_RECEIPT + META_EVIDENCE_SUFFIX, EvidenceClass.SEMANTIC.name.lowercase())
        if (numbers.size > 1) put(META_ENTITY_RECEIPT + META_MORE_SUFFIX, altValue(numbers))
    }
}

internal fun looksLikeReceiptMarker(word: String): Boolean {
    val folded = foldOcr(word).trim { !it.isLetterOrDigit() }
    return folded.isNotEmpty() && FOLDED_RECEIPT_MARKERS.any { it in folded }
}

private val RECEIPT_MARKER_STEMS = listOf("квитан", "receipt")

private val FOLDED_RECEIPT_MARKERS = RECEIPT_MARKER_STEMS.map(::foldOcr)

private val RECEIPT_SHAPED = Regex("""(?<![\p{L}\d-])[A-Z0-9]{2,}(?:-[A-Z0-9]{2,}){0,5}(?![\p{L}\d-])""")
