package com.point.core.flow

import com.point.core.model.Provenance

const val META_ENTITY_ADDRESS = META_ENTITY_PREFIX + "address"

fun addressLines(text: String): List<String> =
    text.lineSequence()
        .map { it.replace(ADDRESS_EDGE_NOISE, "").trim() }
        .filter { addressForm(it) != null }
        .distinct()
        .toList()

private val ADDRESS_EDGE_NOISE = Regex("""^[^\p{L}\p{Nd}]+|[^\p{L}\p{Nd}.]+$""")

fun addressFacts(text: String, from: Provenance = Provenance.OCR): Map<String, String> {
    val lines = addressLines(text)
    val first = lines.firstOrNull() ?: return emptyMap()
    return buildMap {
        put(META_ENTITY_ADDRESS, first)

        put(META_ENTITY_ADDRESS + META_SOURCE_SUFFIX, from.wire)

        put(
            META_ENTITY_ADDRESS + META_EVIDENCE_SUFFIX,
            formEvidence(META_ENTITY_ADDRESS, first).joinToString(",") { it.name.lowercase() },
        )
        if (lines.size > 1) put(META_ENTITY_ADDRESS + META_MORE_SUFFIX, altValue(lines))
    }
}

/**
 * Правдоподобие чужой адресной находки (#632, решение владельца: «проверять
 * правдоподобие адреса»). ML Kit звал адресом товарную строку «Розчинник
 * Уайт-Спірит ХімРезерв 1л», а расширение до строки усиливало ошибку. Адресом
 * считается строка адресной формы либо уличный маркер с номером; слово-мешанина
 * алфавитов («ZeHTpaJIbHa», прогон 2026-08-09) — мусор чтения, не адрес.
 */
fun plausibleAddress(value: String): Boolean {
    val line = value.trim()
    if (line.isEmpty()) return false
    if (line.words().any(::mixedScriptWord)) return false
    if (addressForm(line) != null) return true
    return line.hasStreetMarker() && line.any(Char::isDigit)
}

private fun mixedScriptWord(word: String): Boolean {
    val letters = word.filter(Char::isLetter)
    val cyr = letters.count { it in 'Ѐ'..'ӿ' }
    return cyr > 0 && cyr < letters.length
}

internal enum class AddressForm {

    STREET,

    AREA,
}

internal fun addressForm(line: String): AddressForm? {
    if (line.length > MAX_ADDRESS_LINE) return null
    val parts = line.split(',').map { it.trim() }
    if (parts.size < 2 || parts.size > MAX_ADDRESS_PARTS) return null
    if (parts.any { it.isEmpty() || it.words().size > MAX_PART_WORDS }) return null

    val head = parts.dropLast(1)
    val last = parts.last()

    if (!head.all { it.isNamePart() }) return null

    if (!head.all { it.isProperName() || it.hasStreetMarker() }) return null

    if (last.words().lastOrNull()?.isAreaMarker() == true) return AddressForm.AREA

    if (HOUSE_NUMBER.matches(last) && (head.size >= 2 || head.any { it.hasStreetMarker() })) {
        return AddressForm.STREET
    }

    if (parts.any { it.hasStreetMarker() } && last.isNamePart()) return AddressForm.STREET
    return null
}

private const val MAX_ADDRESS_LINE = 96

private const val MAX_ADDRESS_PARTS = 4

private const val MAX_PART_WORDS = 3

private fun String.isNamePart(): Boolean =
    none(Char::isDigit) && LETTER_RUN.containsMatchIn(this)

private fun String.isProperName(): Boolean =
    words().any { w -> w.firstOrNull(Char::isLetter)?.isUpperCase() == true }

private val LETTER_RUN = Regex("""\p{L}{3,}""")

private fun String.words(): List<String> = split(SPACES).filter { it.any(Char::isLetterOrDigit) }

private val SPACES = Regex("""\s+""")

private val HOUSE_NUMBER = Regex("""(?iu)(?:№\s?)?\d{1,4}\s?\p{L}?(?:\s?[/-]\s?\d{1,3}\s?\p{L}?)?\.?""")

private fun String.isAreaMarker(): Boolean =
    foldOcr(trim { !it.isLetterOrDigit() && it != '-' }) in FOLDED_AREA_MARKERS

private val AREA_MARKERS = listOf("обл", "область", "області", "области", "р-н", "район", "округ")

private val FOLDED_AREA_MARKERS = AREA_MARKERS.map(::foldOcr).toSet()

private fun String.hasStreetMarker(): Boolean =
    words().any { w -> foldOcr(w).trim { !it.isLetterOrDigit() } in FOLDED_STREET_MARKERS }

private val STREET_MARKERS = listOf(
    "вул", "вулиця", "ул", "улица", "просп", "проспект", "пров", "провулок", "переулок",
    "бульв", "бул", "бульвар", "площа", "площадь", "майдан", "наб", "набережна", "набережная",
    "шосе", "шоссе", "проїзд", "проезд",
)

private val FOLDED_STREET_MARKERS = STREET_MARKERS.map(::foldOcr).toSet()
