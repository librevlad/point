package com.point.core.flow

/**
 * Протокол понимания: строгий контракт «KEY=значение» между Point и моделью и разбор
 * ответа в кандидатов полей. Один протокол на все поверхности — телефон добавляет к нему
 * слой атомов и судью, компьютер живёт без них; словарь и парсер общие.
 */
val UNDERSTAND_CONTRACT_KEYS: Map<String, String> = mapOf(
    "PHONE" to "phone",
    "EMAIL" to "email",
    "URL" to "url",
    "ADDRESS" to "address",
    "DATE" to "date",
    "CARD" to "card",
    "TRACK" to "track",
    "METER" to "meter",
    "GEO" to "geo",
    "PLACE" to "place",
    "AMOUNT" to "amount",
    "RECEIPT" to "receipt",
    "SUBJECT" to "subject",
)

fun parseFieldCandidates(answer: String): ParsedUnderstanding {
    val fields = LinkedHashMap<String, MutableList<FieldCandidate>>()
    val single = LinkedHashMap<String, String>()
    answer.lineSequence().forEach { raw ->
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim().uppercase()
        val rest = line.substring(eq + 1).trim()
        if (rest.isEmpty()) return@forEach
        when {
            key == "TYPE" -> rest.lowercase().takeIf { it in KNOWN_SEMANTIC_TAGS }
                ?.let { single.putIfAbsent(META_SEMANTIC_TYPE, it) }
            key == "SUMMARY" -> rest.takeIf { !saysNothing(it) }
                ?.let { single.putIfAbsent(META_SEMANTIC_SUMMARY, it.take(120)) }
            else -> UNDERSTAND_CONTRACT_KEYS[key]?.let { suffix ->
                val metaKey = META_ENTITY_PREFIX + suffix
                val candidate = splitCandidate(rest) ?: return@forEach

                // Форма IBAN — не трек: «UA79…» с квитанции становился готовым
                // «Отследить отправление» (живой прогон 2026-08-09).
                if (suffix == "track" && looksLikeIban(candidate.text)) return@forEach

                // «Голое время это никогда не дата, это мусор» (#651): 11:09 из чата
                // становилось «Нашёл дату».
                if (suffix == "date" && bareClock(candidate.text)) return@forEach

                // Несколько дат в одном значении — несколько кандидатов: «26.04.2026
                // 26.04.2026» с чека рождало слипшийся спор (живой прогон 2026-08-09).
                val pieces = if (suffix == "date") {
                    splitHumanDates(candidate.text).map { candidate.copy(text = it) }
                } else {
                    listOf(candidate)
                }
                val bucket = fields.getOrPut(metaKey) { mutableListOf() }
                pieces.forEach { piece ->
                    if (bucket.size < MAX_FIELD_CANDIDATES && bucket.none { it.text == piece.text && it.ids == piece.ids }) {
                        bucket += piece
                    }
                }
            }
        }
    }
    return ParsedUnderstanding(fields, single)
}

data class ParsedUnderstanding(
    val fields: Map<String, List<FieldCandidate>>,
    val single: Map<String, String>,
)

fun splitCandidate(rest: String): FieldCandidate? {
    if (saysNothing(rest)) return null
    val m = TRAILING_IDS.find(rest)
    if (m != null) {
        val ids = m.groupValues[2].split(',')
            .flatMap { part -> part.trim().split(WHITESPACE) }
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("rule=") }
            .map(::bareIndexId)
        if (ids.isNotEmpty() && ids.all { ID_SHAPED.matches(it) }) {
            val text = m.groupValues[1].trim()
            return if (text.isEmpty() || saysNothing(text)) null else FieldCandidate(text, ids)
        }
    }
    return FieldCandidate(rest)
}

// Контракт требует отвечать NONE на весь документ, но модели пишут «None»/«null»/
// «не найдено» и в отдельные поля — это отсутствие значения, а не значение
// (живой прогон 2026-08-08: семь действий со значением «None»).
private val NO_VALUE = setOf(
    "none", "null", "nil", "n/a", "na", "-", "—", "–",
    "нет", "не найдено", "не найдена", "отсутствует",
    "немає", "не знайдено", "відсутнє", "відсутній",
)

fun saysNothing(text: String): Boolean = text.trim().trim('.').lowercase() in NO_VALUE

private val IBAN_SHAPED = Regex("""[A-Z]{2}\d{2}[A-Z0-9]{11,30}""")

private fun looksLikeIban(text: String): Boolean =
    IBAN_SHAPED.matches(text.filterNot(Char::isWhitespace).uppercase())

private val TRAILING_IDS = Regex("""^(.*?)\s*\[([^\[\]]+)]$""")
private val ID_SHAPED = Regex("""[A-Za-z]+\d+""")
private val WHITESPACE = Regex("""\s+""")
