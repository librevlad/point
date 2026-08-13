package com.point.core.flow

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType

/**
 * Телефон судит библиотека, а не самодельное правило (#801).
 *
 * Мерка была одна на всё — «от десяти до тринадцати цифр» — и жила в двух копиях: у ответа
 * модели и у офлайн-движка. Она пропускала номер карты, не отличала украинский номер от
 * американского и считала три разные записи одного номера тремя разными знаниями:
 * `067 636 05 60`, `+380676360560` и `0676360560`.
 *
 * Решение владельца 12.08.2026: «отдать libphonenumber, заодно обогащать знания».
 *
 * Библиотека отвечает на вопрос, существует ли такой номер, и заодно рассказывает то, чего
 * Point не знал вовсе: страну и вид номера. Хранится номер в одном виде (E.164), а человеку
 * показывается по-человечески.
 *
 * **Регион.** Номер без `+` разобрать без подсказки страны нельзя: `067…` — украинский
 * мобильный, а в другой стране это другой номер или не номер вовсе. Подсказка приходит
 * снаружи — от локали или SIM, — и `:core:flow` остаётся Android-free.
 */
object PhoneNumbers {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** Куда Point смотрит, когда в номере нет `+`. Задаётся снаружи при старте. */
    @Volatile
    var region: String = DEFAULT_REGION

    /** Существует ли такой номер. Молчаливое «нет» лучше выдуманного «да». */
    fun exists(text: String, region: String = PhoneNumbers.region): Boolean = parse(text, region) != null

    /**
     * Один и тот же номер, записанный по-разному, — одно знание.
     *
     * `067 636 05 60`, `+380676360560` и `0676360560` дают одну строку E.164, и тождество
     * считается по ней, а не по тексту.
     */
    fun same(left: String, right: String, region: String = PhoneNumbers.region): Boolean {
        val a = e164(left, region) ?: return false
        val b = e164(right, region) ?: return false
        return a == b
    }

    /** Как номер хранится: единообразно, без пробелов и скобок. */
    fun e164(text: String, region: String = PhoneNumbers.region): String? =
        parse(text, region)?.let { util.format(it, PhoneNumberUtil.PhoneNumberFormat.E164) }

    /** Как номер показывается человеку: так, как он привык его видеть. */
    fun human(text: String, region: String = PhoneNumbers.region): String? =
        parse(text, region)?.let { number ->
            val format = if (number.countryCode == countryCodeOf(region)) {
                PhoneNumberUtil.PhoneNumberFormat.NATIONAL
            } else {
                PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
            }
            util.format(number, format)
        }

    /** Страна номера по международному коду: «UA», «PL». */
    fun country(text: String, region: String = PhoneNumbers.region): String? =
        parse(text, region)?.let { util.getRegionCodeForNumber(it) }

    /** Вид номера словами человека: мобильный, городской. Неизвестное молчит. */
    fun kind(text: String, region: String = PhoneNumbers.region): String? =
        parse(text, region)?.let {
            when (util.getNumberType(it)) {
                PhoneNumberType.MOBILE -> "мобильный"
                PhoneNumberType.FIXED_LINE -> "городской"
                PhoneNumberType.FIXED_LINE_OR_MOBILE -> null
                PhoneNumberType.TOLL_FREE -> "бесплатный"
                PhoneNumberType.PREMIUM_RATE -> "платный"
                else -> null
            }
        }

    private fun countryCodeOf(region: String): Int =
        runCatching { util.getCountryCodeForRegion(region) }.getOrDefault(0)

    private fun parse(text: String, region: String): com.google.i18n.phonenumbers.Phonenumber.PhoneNumber? {
        val clean = text.trim()
        if (clean.isEmpty()) return null

        // В телефоне не бывает слов. Модель давала чистый «067 636 05 60», а заземление по
        // странице возвращало склейку «Тарасенко Світлана Сергіївна 067 636 05 60» — она и
        // вставала значением телефона (охота 11.08.2026). Библиотека такую склейку разберёт:
        // она ищет номер внутри строки, а нам нужно, чтобы строка номером и была.
        if (WORDY.containsMatchIn(clean)) return null
        return try {
            util.parse(clean, region).takeIf { util.isValidNumber(it) }
        } catch (_: NumberParseException) {
            null
        }
    }

    /** Пока страна не подсказана снаружи. */
    const val DEFAULT_REGION = "UA"
}
