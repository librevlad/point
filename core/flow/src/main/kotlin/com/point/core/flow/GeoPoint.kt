package com.point.core.flow

import com.point.core.model.Provenance

const val META_ENTITY_GEO = META_ENTITY_PREFIX + "geo"

/**
 * Когда снят снимок (#547). Отдельно от `entity.date`: там даты, найденные **в содержимом** —
 * в договоре это срок или число подписи, а не момент съёмки. Смешать их значит сделать
 * «Нашёл дату» словом, которое означает то одно, то другое.
 */
const val META_SHOT_AT = "shot.at"

/** EXIF пишет «2024:03:12 14:07:33»; человеку нужно «12.03.2024, 14:07». */
fun shotDateLabel(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.length < 19) return null
    val date = text.substring(0, 10).split(':', '-', '.')
    val time = text.substring(11, 16)
    if (date.size != 3 || date.any { it.isEmpty() }) return null
    val (year, month, day) = date
    if (year.toIntOrNull() == null || month.toIntOrNull() == null || day.toIntOrNull() == null) return null
    return "$day.$month.$year, $time"
}

private const val MIN_FRACTION = 4

fun geoPoints(text: String): List<String> =
    GEO_SHAPED.findAll(text)
        .mapNotNull { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null

            if (kotlin.math.abs(lat) > MAX_LAT || kotlin.math.abs(lon) > MAX_LON) return@mapNotNull null
            "${m.groupValues[1]}, ${m.groupValues[2]}"
        }
        .distinct()
        .toList()

fun geoFacts(text: String, source: Provenance = Provenance.OCR): Map<String, String> {
    val points = geoPoints(text)
    val first = points.firstOrNull() ?: return emptyMap()
    return buildMap {
        put(META_ENTITY_GEO, first)
        put(META_ENTITY_GEO + META_SOURCE_SUFFIX, source.wire)
        put(META_ENTITY_GEO + META_EVIDENCE_SUFFIX, EvidenceClass.SEMANTIC.name.lowercase())
        if (points.size > 1) put(META_ENTITY_GEO + META_MORE_SUFFIX, altValue(points))
    }
}

private const val MAX_LAT = 90.0
private const val MAX_LON = 180.0

private val GEO_SHAPED = Regex(
    """(?<![\d.,])([-+]?\d{1,2}\.\d{$MIN_FRACTION,8})(?:\s*[,;]\s*|\s+)([-+]?\d{1,3}\.\d{$MIN_FRACTION,8})(?![\d.])""",
)

const val META_ENTITY_PLACE = META_ENTITY_PREFIX + "place"
