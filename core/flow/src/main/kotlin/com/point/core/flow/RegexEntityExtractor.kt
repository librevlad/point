package com.point.core.flow

class RegexEntityExtractor(
    private val region: PhoneRegion = DEFAULT_PHONE_REGION,
) : EntityExtractor {

    override suspend fun extract(text: String): List<Entity> {
        if (text.isBlank()) return emptyList()

        // Речь пишется словами, а не знаками (#1426): расшифровка отдаёт «call plus 380671234567»,
        // и номер без «+» в правило не проходил. Устный «плюс» перед цифрами — тот же знак.
        val spoken = SPOKEN_PLUS.replace(text, "+")
        val found = buildList {

            EMAIL.findAll(text).forEach { add(Entity(EntityType.EMAIL, it.value)) }
            URL.findAll(text).forEach { add(Entity(EntityType.URL, it.value.trimEnd('.', ',', ')'))) }
            CARD.findAll(text).forEach { m ->
                val digits = m.value.filter(Char::isDigit)
                if (luhn(digits)) add(Entity(EntityType.PAYMENT_CARD, m.value.trim()))
            }
            PHONE.findAll(spoken).forEach { add(Entity(EntityType.PHONE, it.value.trim())) }
            MONEY.findAll(text).forEach { add(Entity(EntityType.MONEY, it.value.trim())) }
            DATE.findAll(text).forEach { add(Entity(EntityType.DATE_TIME, it.value.trim())) }
        }

        val unique = found.distinctBy { it.type to it.value.lowercase() }
        return plausibleEntities(unique, text, region.code())
    }

    private companion object {
        val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]{2,}""")

        val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']{3,}""")

        val CARD = Regex("""\b(?:\d[ -]?){13,19}\b""")

        // Точка в конце предложения номеру не мешает («call plus 380671234567.», #1426); точка с
        // цифрой за ней — продолжение числа, а не конец фразы.
        val PHONE = Regex("""(?<![\w.])\+?\d[\d\s()\-]{8,17}\d(?!\w|\.\d)""")

        /** «plus 380…», «плюс 380…» — устный знак «+» перед номером; в Whisper он приходит словом. */
        val SPOKEN_PLUS = Regex("""(?iu)(?<!\p{L})(?:plus|плюс)\s+(?=\d)""")

        val MONEY = Regex(
            """(?iu)\b\d[\d\s.,]{0,12}\s?(?:₽|руб\.?|грн\.?|₴|\$|€|USD|EUR|UAH|RUB)\b""" +
                """|(?:[$€₽₴])\s?\d[\d\s.,]{0,12}""",
        )

        // Месяц словом — по-русски и по-украински с любым окончанием, по-английски полным именем
        // или принятым сокращением (`ENGLISH_MONTH`), днём вперёд или месяцем вперёд
        // («September 11, 2026» — так пишет расшифровка английской речи, #1426). Без года дня
        // нет — правило прежнее: «September 11 at 3 p.m.» остаётся текстом.
        val DATE = Regex(
            """(?iu)\b\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}\b""" +
                """|\b\d{4}-\d{2}-\d{2}\b""" +
                """|\b\d{1,2}\s+(?:$MONTH_WORD)\p{L}*\s+\d{4}\b""" +
                """|\b\d{1,2}(?:st|nd|rd|th)?\s+(?:$ENGLISH_MONTH)(?!\p{L})\.?,?\s+\d{4}\b""" +
                """|(?<!\p{L})(?:$ENGLISH_MONTH)(?!\p{L})\.?\s+\d{1,2}(?:st|nd|rd|th)?,?\s+\d{4}\b""",
        )

        private const val MONTH_WORD =
            "январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр|" +
                "січн|лют|берез|квітн|травн|червн|липн|серпн|вересн|жовтн|листопад|грудн"
    }
}

/** Контрольная сумма платёжной карты; общая для правил и протокола понимания (#1176). */
internal fun luhn(digits: String): Boolean {
    if (digits.length !in 13..19) return false
    var sum = 0
    var double = false
    for (i in digits.indices.reversed()) {
        var d = digits[i] - '0'
        if (double) {
            d *= 2
            if (d > 9) d -= 9
        }
        sum += d
        double = !double
    }
    return sum % 10 == 0
}
