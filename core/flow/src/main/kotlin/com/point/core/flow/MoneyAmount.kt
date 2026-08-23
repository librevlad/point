package com.point.core.flow

import com.point.core.model.Provenance
import java.math.BigDecimal
import java.math.RoundingMode

data class MoneyAmount(

    val value: String,

    val currency: String,
)

const val META_ENTITY_AMOUNT = META_ENTITY_PREFIX + "amount"

const val META_ENTITY_AMOUNT_CURRENCY = META_ENTITY_AMOUNT + ".currency"

private const val AMOUNT_MAX_DIGITS = 12

internal fun amountDigitsFit(value: String): Boolean =
    value.substringBefore(',').substringBefore('.').count(Char::isDigit) in 1..AMOUNT_MAX_DIGITS

/**
 * Сумма — число (#1059, решение владельца): цифры с разрядами и копейками, при них знак и
 * валюта — и ничего больше; арифметика и ноль — не сумма документа (#662).
 *
 * Один судья формы на все пути: правило чтения страницы, разбор ответа модели, заземление по
 * словам страницы в судье и зрячее чтение снимка. Прежде судья считал одни цифры — и слово
 * чека «TAX1», и «0» из слипшейся строки «TAX1 0» проходили в сумму и вставали главным
 * фактом чека Family Dollar, а настоящие 2.00, 0.18 и 2.18 уходили в «ещё».
 *
 * Знаком валюты считается любой знак валюты Unicode, а не перечисленные здесь: «¥1200» и
 * «₹250» — такие же числа, как «$2.18», и закрытый список выбрасывал их из знания. Буквы при
 * числе валютой сами по себе не становятся: именно так «TAX1» и вставало суммой, — поэтому
 * словами валюту знают только названные.
 */
fun amountFits(value: String): Boolean =
    AMOUNT_VALUE.matches(value.trim()) && amountDigitsFit(value) && !zeroAmount(value)

/**
 * Ноль — не сумма документа (#662): «Комісія (грн) 0.00» с квитанции вставало «ещё»-суммой
 * рядом с настоящим платежом. О деньгах, ради которых человек открыл объект, ноль не говорит
 * ничего.
 */
private fun zeroAmount(value: String): Boolean =
    value.filter { it.isDigit() || it == ',' || it == '.' }
        .replace(',', '.')
        .toBigDecimalOrNull()?.signum() == 0

/**
 * Величина суммы как число: разряды сняты, копейки — после последнего разделителя, если за ним
 * одна-две цифры. «1,048» и «1.048» — тысячи, «2,18» и «2.18» — копейки. Не-число — `null`.
 */
fun amountValue(value: String): BigDecimal? {
    if (!amountFits(value)) return null
    val marks = value.filter { it.isDigit() || it == ',' || it == '.' }
    val cut = marks.indexOfLast { it == ',' || it == '.' }
    val cents = cut >= 0 && marks.length - cut - 1 <= CENTS_DIGITS
    val whole = (if (cents) marks.take(cut) else marks).filter(Char::isDigit)
    val number = (if (cents) whole + "." + marks.substring(cut + 1) else whole).toBigDecimalOrNull()

    // Знак стоит до числа, а валюта — с любой его стороны: «-$2.18» и «$-2.18» — одинаково
    // минус. Прежде минус искали вплотную к цифре, и «-$2.18» уходил плюсом.
    val negative = value.takeWhile { !it.isDigit() }.any { it == '-' || it == '−' }
    return if (number != null && negative) number.negate() else number
}

private const val CENTS_DIGITS = 2

/**
 * Итог среди названных сумм (#1059, решение владельца): наибольшее из чисел. Подпись «итого»
 * сильнее величины и спрашивается раньше — правилом страницы в [amountFacts], у судьи —
 * уликой подписи.
 *
 * Прочтение судьи несёт валюту в себе — «$2.18», — поэтому здесь она читается из самой записи.
 */
fun mainAmount(values: List<String>): String? {
    if (values.isEmpty()) return null
    return biggest(values.map { MoneyAmount(it, currencyMark(it).orEmpty()) }).value
}

/**
 * Наибольшая из сумм — итог, когда подпись его не назвала.
 *
 * Числа разных валют несравнимы: 5 € и 5 $ — не одно и то же, и большего среди них нет.
 * Тогда итог не выбирается вовсе и остаётся первое названное, как было до правила. Валюта,
 * которую документ не назвал, ничему не противоречит: на чеке она стоит раз на строку, а то
 * и одна на весь лист.
 */
private fun biggest(amounts: List<MoneyAmount>): MoneyAmount {
    val named = amounts.mapNotNull { it.currency.lowercase().ifEmpty { null } }.distinct()
    if (amounts.size < 2 || named.size > 1) return amounts.first()
    return amounts.maxBy { amountValue(it.value) ?: BigDecimal.ZERO }
}

/** Валютная пометка при числе: знак валюты Unicode или названное словом; нет — `null`. */
private fun currencyMark(value: String): String? =
    CURRENCY_FOUND.find(value)?.value?.lowercase()

/**
 * Подписана ли эта сумма как итог документа (#1059, решение владельца).
 *
 * Подпись стоит в той же строке или в строке-заголовке над числом — теми же словами, какими
 * её знает судья улик (`FIELD_MARKERS`). Величина одна итога не называет: на чеке Family
 * Dollar наибольшее — «CASH 2.25», отданные деньги, а заплачено 2.18.
 */
private fun labelledTotal(lines: List<String>, value: String): Boolean {
    val at = lines.indexOfFirst { standsIn(it, value) }
    if (at < 0) return false
    if (totalWords(lines[at])) return true

    // Строка над числом — подпись колонки, только если своих чисел в ней нет: «Всього, грн»
    // сверху и число снизу — одна подпись, разорванная переносом.
    val above = lines.getOrNull(at - 1) ?: return false
    return moneyAmounts(above).isEmpty() && totalWords(above)
}

/** Стоит ли в строке именно эта сумма: «2.18» внутри «12.18» — другое число, не она. */
private fun standsIn(line: String, value: String): Boolean {
    var at = line.indexOf(value)
    while (at >= 0) {
        val before = line.getOrNull(at - 1)
        val after = line.getOrNull(at + value.length)
        if (before?.isDigit() != true && before != '.' && before != ',' && after?.isDigit() != true) {
            return true
        }
        at = line.indexOf(value, at + 1)
    }
    return false
}

private fun totalWords(line: String): Boolean {
    val words = " " + line.lowercase().replace(NOT_LETTER, " ").trim() + " "

    // Подпись — слово целиком: «SUBTOTAL» итога не объявляет, «CASH TOTAL» объявляет.
    return TOTAL_MARKERS.any { " $it " in words }
}

private val NOT_LETTER = Regex("[^\\p{L}]+")

/** Слова, которыми документ называет свой итог (#1059). Одни и те же у правила и у судьи. */
internal val TOTAL_MARKERS = listOf("total", "итого", "всього", "до сплати", "к оплате")

fun moneyAmounts(text: String): List<MoneyAmount> =
    AMOUNT_SHAPED.findAll(text)
        .map { m ->

            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            val currency = m.groupValues[1].ifEmpty { m.groupValues[4] }
            MoneyAmount(value.trim(), currency.trim())
        }
        .filter { amountFits(it.value) }
        .distinctBy { it.value.filter { c -> c.isDigit() || c == ',' || c == '.' } + "|" + it.currency.lowercase() }
        .toList()

fun amountFacts(text: String, source: Provenance = Provenance.OCR): Map<String, String> {
    val amounts = moneyAmounts(text)
    if (amounts.isEmpty()) return totalFacts(arithmeticTotals(text), source)

    // Главная сумма — итог: подписанная «итого» побеждает, а если подписи нет — наибольшее
    // из чисел (#1059, решение владельца); остальные остаются в «ещё». Прежде главной
    // вставала первая встреченная, и у чека это подытог или налог; одна величина тоже не
    // спасает — на чеке Family Dollar наибольшее это «CASH 2.25», отданные деньги.
    val lines = text.lines()
    val pool = amounts.filter { labelledTotal(lines, it.value) }.ifEmpty { amounts }
    val main = biggest(pool)
    val values = amounts.map { it.value }.distinct()
    return buildMap {
        put(META_ENTITY_AMOUNT, main.value)
        put(META_ENTITY_AMOUNT_CURRENCY, main.currency)

        put(META_ENTITY_AMOUNT + META_SOURCE_SUFFIX, source.wire)

        put(META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX, EvidenceClass.SEMANTIC.name.lowercase())
        if (values.size > 1) put(META_ENTITY_AMOUNT + META_MORE_SUFFIX, altValue(values))
    }
}

private fun totalFacts(totals: List<String>, source: Provenance): Map<String, String> {
    val first = totals.firstOrNull() ?: return emptyMap()
    return buildMap {
        put(META_ENTITY_AMOUNT, first)

        put(META_ENTITY_AMOUNT + META_SOURCE_SUFFIX, source.wire)
        put(META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX, EvidenceClass.ARITHMETIC.name.lowercase())

        if (totals.size > 1) put(META_ENTITY_AMOUNT + META_MORE_SUFFIX, altValue(totals))
    }
}

fun arithmeticTotals(text: String): List<String> {
    val sums = ARITHMETIC_SHAPED.findAll(text).mapNotNull(::checkedSum).toList()

    val (finals, intermediates) = sums.withIndex().partition { (i, sum) ->
        sums.drop(i + 1).none { later -> later.operands.any { sameNumber(it, sum.total) } }
    }
    return (finals + intermediates).map { it.value.total }.distinctBy(::numericKey)
}

private class CheckedSum(val operands: List<String>, val total: String)

private fun checkedSum(m: MatchResult): CheckedSum? {
    val left = decimalOrNull(m.groupValues[1]) ?: return null
    val right = decimalOrNull(m.groupValues[3]) ?: return null
    val printed = decimalOrNull(m.groupValues[4]) ?: return null

    if (!amountDigitsFit(m.groupValues[4])) return null
    val computed = apply(left, m.groupValues[2], right) ?: return null
    if (!agrees(computed, printed)) return null
    return CheckedSum(listOf(m.groupValues[1], m.groupValues[3]), m.groupValues[4].trim())
}

private fun agrees(computed: BigDecimal, printed: BigDecimal): Boolean {
    if (computed.compareTo(printed) == 0) return true
    if (printed.scale() < MONEY_SCALE) return false
    return computed.setScale(printed.scale(), RoundingMode.HALF_UP).compareTo(printed) == 0
}

private const val MONEY_SCALE = 2

private fun apply(a: BigDecimal, op: String, b: BigDecimal): BigDecimal? = when (op) {
    "+" -> a + b
    "-", "−", "–" -> a - b

    "*", "×", "x", "X", "х", "Х" -> a * b

    "/", ":", "÷" -> if (b.signum() == 0) null else a.divide(b, DIVISION_SCALE, RoundingMode.HALF_UP)
    else -> null
}

private const val DIVISION_SCALE = 6

private fun decimalOrNull(raw: String): BigDecimal? =
    raw.replace(SPACE_IN_NUMBER, "").replace(',', '.').toBigDecimalOrNull()

private val SPACE_IN_NUMBER = Regex(H_SPACE)

private fun numericKey(value: String): String =
    value.filter { it.isDigit() || it == ',' || it == '.' }.replace(',', '.')

private fun sameNumber(a: String, b: String): Boolean = numericKey(a) == numericKey(b)

private val ARITHMETIC_SHAPED = Regex(
    "(?<![\\d.,])($AMOUNT_NUMBER)$H_SPACE*([-+*/:−–×÷xXхХ])$H_SPACE*" +
        "($AMOUNT_NUMBER)$H_SPACE*=$H_SPACE*($AMOUNT_NUMBER)(?![\\d])(?![.,]\\d)",
)

private val CURRENCIES = listOf(
    "₴", "грн.", "грн", "UAH",
    "$", "USD", "€", "EUR", "£", "GBP",
    "₽", "руб.", "руб", "RUB",
    "zł", "PLN",
)

private val CURRENCY_ALT =
    CURRENCIES.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

/**
 * Валюта при числе: любой знак валюты Unicode (`\p{Sc}`) или названная словом (#1059).
 * Список знаков закрытым быть не может — иен, рупий и злотых в мире больше, чем в этом файле.
 */
private val CURRENCY_MARK = "(?:\\p{Sc}|$CURRENCY_ALT)"

private val CURRENCY_FOUND = Regex("(?iu)$CURRENCY_MARK")

private const val H_SPACE = "[ \\t\\u00A0]"

private const val AMOUNT_NUMBER =
    "(?:\\d{1,3}(?:$H_SPACE\\d{3})+|\\d{1,$AMOUNT_MAX_DIGITS})(?:[.,]\\d{1,2})?"

private const val AMOUNT_LEFT = "(?<![\\d.,])(?<![\\d.,]$H_SPACE)"
private const val AMOUNT_RIGHT = "(?![\\d])(?![.,:]\\d)(?!$H_SPACE\\d)"

private val AMOUNT_SHAPED = Regex(
    "(?iu)(?:" +
        "(?<![\\p{L}])($CURRENCY_MARK)\\)?[ \\t\\u00A0]*\\n?[ \\t\\u00A0]*" +
        AMOUNT_LEFT + "($AMOUNT_NUMBER)" + AMOUNT_RIGHT +
        "|" +
        AMOUNT_LEFT + "($AMOUNT_NUMBER)" + AMOUNT_RIGHT +

        // Знак валюты вплотную к следующему числу принадлежит ему, а не предыдущему: в
        // «TAX1 $0.18» доллар — при 18 копейках, и «1» из подписи суммой не становится.
        "[\\s\\u00A0]{0,2}($CURRENCY_MARK)(?![\\p{L}\\d])" +
        ")",
)

/**
 * Число суммы в любой записи: разряды пробелом, точкой, запятой или апострофом («12 500»,
 * «1,048.64», «1.048,64»), копейки — одна-две цифры после последнего разделителя.
 */
private const val AMOUNT_NUMBER_ANY =
    "(?:\\d{1,3}(?:[ \\u00A0.,']\\d{3})+|\\d{1,$AMOUNT_MAX_DIGITS})(?:[.,]\\d{1,2})?"

private const val AMOUNT_SIGN = "[-−]?$H_SPACE*"

/**
 * Значение суммы целиком (#1059): число, при нём — знак и валюта, больше ничего. Знак пишут
 * и до валюты, и после неё: «-$2.18» — такой же минус, как «$-2.18».
 */
private val AMOUNT_VALUE = Regex(
    "(?iu)$AMOUNT_SIGN(?:$CURRENCY_MARK$H_SPACE*)?$AMOUNT_SIGN$AMOUNT_NUMBER_ANY" +
        "(?:$H_SPACE*$CURRENCY_MARK)?",
)
