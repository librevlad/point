package com.point.core.flow

/**
 * Сущности из текста без единой чужой библиотеки — чистый Kotlin (#585).
 *
 * На телефоне их ищет ML Kit, и это правильно: он умеет больше и знает язык. Но ML Kit — часть
 * Android, а Point живёт ещё и на компьютере, где никакого ML Kit нет и не будет. Пока его не
 * было, компьютер не умел ничего из главного: увидеть в тексте телефон, почту, ссылку, сумму —
 * то, ради чего человек в Point и приходит.
 *
 * Здесь ищется меньше, чем на телефоне, и **честно** меньше: только то, у чего есть надёжная
 * форма. Догадки о том, адрес перед нами или просто слово с запятой, оставлены ML Kit — на
 * компьютере лучше не найти, чем найти неправду.
 *
 * Результат проходит тот же фильтр правдоподобия ([plausibleEntities]), что и находки ML Kit:
 * длинный номер накладной, притворившийся телефоном, отсеивается одинаково на обоих устройствах.
 */
class RegexEntityExtractor : EntityExtractor {

    override suspend fun extract(text: String): List<Entity> {
        if (text.isBlank()) return emptyList()
        val found = buildList {
            // Порядок важен: почта содержит внутри себя то, что похоже на ссылку, а ссылка —
            // то, что похоже на телефон. Сначала длинное и определённое, потом короткое.
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
        // Одна и та же строка не должна приезжать дважды: почта, найденная и как почта, и как
        // часть ссылки, — это один факт, а не два.
        val unique = found.distinctBy { it.type to it.value.lowercase() }
        return plausibleEntities(unique, text)
    }

    private companion object {
        val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]{2,}""")

        /** Ссылка с протоколом или узнаваемая по `www.`: без этого «т.е.» станет доменом. */
        val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']{3,}""")

        /**
         * Карта — четыре группы по четыре цифры. Проверяется Луном тут же: без него любой
         * шестнадцатизначный номер документа становился бы «картой», и человек получал бы
         * действие «скопировать карту» на накладной.
         */
        val CARD = Regex("""\b(?:\d[ -]?){13,19}\b""")

        /**
         * Телефон: необязательный плюс, 10–15 цифр с разделителями. Скобки и тире допускаются,
         * буквы — нет.
         */
        val PHONE = Regex("""(?<![\w.])\+?\d[\d\s()\-]{8,17}\d(?![\w.])""")

        /** Сумма с валютой — только со знаком или названием: голое число суммой не считается. */
        val MONEY = Regex(
            """(?iu)\b\d[\d\s.,]{0,12}\s?(?:₽|руб\.?|грн\.?|₴|\$|€|USD|EUR|UAH|RUB)\b""" +
                """|(?:[$€₽₴])\s?\d[\d\s.,]{0,12}""",
        )

        /** Дата: 05.08.2026, 5 августа 2026, 2026-08-05. Время без даты не берётся — оно ничего не адресует. */
        val DATE = Regex(
            """(?iu)\b\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4}\b""" +
                """|\b\d{4}-\d{2}-\d{2}\b""" +
                """|\b\d{1,2}\s+(?:январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр)[а-яё]*\s+\d{4}\b""",
        )

        /** Луна — та же проверка, что у банков: последняя цифра сходится с остальными. */
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
