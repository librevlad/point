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

    /**
     * Страна устройства — **одна из подсказок**, а не приговор (#936).
     *
     * Номер без `+` без подсказки не разобрать. Раньше подсказка была одна и бралась у
     * телефона: у украинца с телефоном на английском страна оказывалась `US`, и его
     * собственные номера переставали существовать — молча, все до единого. Живая охота
     * 13.08.2026: модель ответила `PHONE=067 636 05 60`, а Point выбросил.
     *
     * Страна устройства — не страна документа. Человек в Польше открывает украинский счёт;
     * номер в документе от этого не исчезает.
     */
    @Volatile
    var region: String = DEFAULT_REGION

    /**
     * Существует ли такой номер хотя бы в одной из правдоподобных стран.
     *
     * Решение владельца 13.08.2026: «Несколько стран, годится любая». Принятая цена — на
     * грязном OCR ложных номеров станет чуть больше.
     */
    fun exists(text: String, region: String = PhoneNumbers.region): Boolean = countryOf(text, region) != null

    /**
     * Первая страна, в которой номер существует, — или `null`, если не существует нигде.
     *
     * Порядок подсказок не случаен: сначала та, что назвал вызывающий (страна устройства
     * или подсказка из самого документа), потом остальные, где Point живёт.
     */
    fun countryOf(text: String, region: String = PhoneNumbers.region): String? =
        candidateRegions(text, region).firstOrNull { parse(text, it) != null }

    private fun candidateRegions(text: String, region: String): List<String> =
        if (writtenLikeAPhone(text)) hintsFrom(region) else listOf(region.uppercase())

    /**
     * Подсказки страны: названная первой, дальше — остальные близкие.
     *
     * Список короткий нарочно: чем он длиннее, тем больше случайных чисел окажутся чьими-то
     * настоящими номерами. Здесь страны, откуда к человеку реально приходят документы.
     */
    private fun hintsFrom(first: String): List<String> =
        (listOf(first.uppercase()) + NEARBY).distinct()

    /**
     * Стоит ли вообще искать этот текст по чужим странам.
     *
     * Чем больше стран, тем больше случайных чисел оказываются чьими-то настоящими номерами:
     * «4507 1234» и контрольная цифра накладной начинали проходить за телефон. Поэтому чужие
     * страны пробуются только у того, что **написано как номер**: с плюсом, с ведущим нулём
     * или с разделителями внутри. Голая цепочка цифр — это идентификатор, а не телефон.
     */
    private fun writtenLikeAPhone(text: String): Boolean {
        val clean = text.trim()
        val digits = clean.count(Char::isDigit)
        val shaped = clean.startsWith("+") || clean.startsWith("0") ||
            clean.any { it == ' ' || it == '-' || it == '(' || it == ')' }
        return shaped && digits in PHONE_DIGITS
    }

    /**
     * Сколько цифр бывает в номере, который человек пишет в документе.
     *
     * Короче — это обрывок: «4507 1234» с квитанции и контрольная цифра накладной начинали
     * проходить за телефон, стоило добавить стран. Длиннее — идентификатор.
     */
    private val PHONE_DIGITS = 9..13

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

    /** Разобрать номер той страной, в которой он существует. */
    private fun parseAnywhere(text: String, region: String) =
        countryOf(text, region)?.let { parse(text, it) }

    /** Как номер хранится: единообразно, без пробелов и скобок. */
    fun e164(text: String, region: String = PhoneNumbers.region): String? =
        parseAnywhere(text, region)?.let { util.format(it, PhoneNumberUtil.PhoneNumberFormat.E164) }

    /** Как номер показывается человеку: так, как он привык его видеть. */
    fun human(text: String, region: String = PhoneNumbers.region): String? =
        parseAnywhere(text, region)?.let { number ->
            val format = if (number.countryCode == countryCodeOf(region)) {
                PhoneNumberUtil.PhoneNumberFormat.NATIONAL
            } else {
                PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL
            }
            util.format(number, format)
        }

    /**
     * Страна номера — только когда она **одна**.
     *
     * Существование номера проверяется по нескольким странам (#936), и десятизначный номер
     * нередко оказывается настоящим сразу в двух: `918-682-1551` годится и Америке, и
     * Германии. Сказать в таком случае «Германия» значит выдумать знание. Молчание честнее:
     * номер есть, страна неизвестна.
     *
     * Записанный международно — с `+` — сомнений не оставляет, и страна называется всегда.
     */
    fun country(text: String, region: String = PhoneNumbers.region): String? {
        val fits = candidateRegions(text, region).mapNotNull { parse(text, it) }
            .map { util.getRegionCodeForNumber(it) }
            .distinct()
        return fits.singleOrNull()
    }

    /** Вид номера словами человека: мобильный, городской. Неизвестное молчит. */
    fun kind(text: String, region: String = PhoneNumbers.region): String? =
        parseAnywhere(text, region)?.let {
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

    /**
     * Страны, откуда к человеку приходят документы, помимо названной подсказкой.
     *
     * Список держится коротким: каждая лишняя страна превращает часть случайных чисел в
     * чьи-то настоящие номера.
     */
    private val NEARBY = listOf("UA", "PL", "DE", "US")
}
