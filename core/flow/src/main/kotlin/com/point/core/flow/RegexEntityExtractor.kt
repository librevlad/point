package com.point.core.flow

class RegexEntityExtractor(
    private val region: PhoneRegion = DEFAULT_PHONE_REGION,
) : EntityExtractor {

    override suspend fun extract(text: String): List<Entity> {
        if (text.isBlank()) return emptyList()
        val found = buildList {

            EMAIL.findAll(text).forEach { add(Entity(EntityType.EMAIL, it.value)) }
            URL.findAll(text).forEach { add(Entity(EntityType.URL, it.value.trimEnd('.', ',', ')'))) }
            CARD.findAll(text).forEach { m ->
                val digits = m.value.filter(Char::isDigit)
                if (luhn(digits)) add(Entity(EntityType.PAYMENT_CARD, m.value.trim()))
            }
            PHONE.findAll(text).forEach { add(Entity(EntityType.PHONE, it.value.trim())) }
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

        val PHONE = Regex("""(?<![\w.])\+?\d[\d\s()\-]{8,17}\d(?![\w.])""")

        val MONEY = Regex(
            """(?iu)\b\d[\d\s.,]{0,12}\s?(?:₽|руб\.?|грн\.?|₴|\$|€|USD|EUR|UAH|RUB)\b""" +
                """|(?:[$€₽₴])\s?\d[\d\s.,]{0,12}""",
        )

        val DATE = Regex(
            """(?iu)\b\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}\b""" +
                """|\b\d{4}-\d{2}-\d{2}\b""" +
                """|\b\d{1,2}\s+(?:январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр)[а-яё]*\s+\d{4}\b""",
        )

        fun luhn(digits: String): Boolean {
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
    }
}
