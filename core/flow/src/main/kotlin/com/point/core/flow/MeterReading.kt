package com.point.core.flow

import com.point.core.model.Provenance

data class MeterReading(

    val value: String,

    val unit: String,
)

const val META_ENTITY_METER = META_ENTITY_PREFIX + "meter"

const val META_ENTITY_METER_UNIT = META_ENTITY_METER + ".unit"

internal const val METER_MIN_DIGITS = 3

internal const val METER_MAX_DIGITS = 8

internal fun meterDigitsFit(value: String): Boolean =
    value.substringBefore(',').substringBefore('.').count(Char::isDigit) in
        METER_MIN_DIGITS..METER_MAX_DIGITS

fun meterReadings(text: String): List<MeterReading> =
    METER_SHAPED.findAll(text)
        .map { MeterReading(it.groupValues[1].trim(), it.groupValues[2].trim()) }
        .filter { meterDigitsFit(it.value) }
        .distinctBy { it.value.filter(Char::isDigit) + "|" + it.unit.lowercase() }
        .toList()

fun meterFacts(text: String, source: Provenance = Provenance.OCR): Map<String, String> {
    val readings = meterReadings(text)
    val first = readings.firstOrNull() ?: return emptyMap()

    val values = readings.map { it.value }.distinct()
    return buildMap {
        put(META_ENTITY_METER, first.value)
        put(META_ENTITY_METER_UNIT, first.unit)

        put(META_ENTITY_METER + META_SOURCE_SUFFIX, source.wire)

        put(META_ENTITY_METER + META_EVIDENCE_SUFFIX, EvidenceClass.SEMANTIC.name.lowercase())
        if (values.size > 1) put(META_ENTITY_METER + META_MORE_SUFFIX, altValue(values))
    }
}

fun meterWithoutDrumZeros(value: String): String? {
    val v = value.trim()
    if (!v.startsWith('0') || !DRUM_NUMBER.matches(v)) return null
    val cut = v.indexOfFirst { it == ',' || it == '.' }
    val whole = if (cut < 0) v else v.substring(0, cut)
    val fraction = if (cut < 0) "" else v.substring(cut)

    val significant = whole.replaceFirst(DRUM_ZEROS, "")

    return (significant.ifEmpty { "0" } + fraction).takeIf { it != v }
}

private val DRUM_ZEROS = Regex("^[0\\s\\u00A0]+")

private val DRUM_NUMBER = Regex("\\d+(?:[\\s\\u00A0]\\d+)*(?:[.,]\\d+)?")

fun fieldHint(key: String, value: String): String? = when (key) {
    META_ENTITY_METER -> meterWithoutDrumZeros(value)
    else -> null
}

private val METER_UNITS = listOf(
    "кВт·ч", "кВт*ч", "кВт.ч", "кВтч", "кВт·год", "кВт*год", "кВт.год", "кВтгод", "kWh", "kW·h",
    "м³", "м3", "куб.м", "м.куб", "m³", "m3",
    "Гкал", "Gcal",
)

private val METER_SHAPED = Regex(
    "(?iu)(?<![\\d.,])((?:\\d{1,3}(?:[ \\t\\u00A0]\\d{3})+|\\d{1,$METER_MAX_DIGITS})" +
        "(?:[.,]\\d{1,3})?)" +
        "\\s{0,2}(" +
        METER_UNITS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
        ")(?![\\p{L}\\d])",
)
