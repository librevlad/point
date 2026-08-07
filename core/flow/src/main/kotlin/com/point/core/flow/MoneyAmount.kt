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

fun moneyAmounts(text: String): List<MoneyAmount> =
    AMOUNT_SHAPED.findAll(text)
        .map { m ->

            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            val currency = m.groupValues[1].ifEmpty { m.groupValues[4] }
            MoneyAmount(value.trim(), currency.trim())
        }
        .filter { amountDigitsFit(it.value) }
        .distinctBy { it.value.filter { c -> c.isDigit() || c == ',' || c == '.' } + "|" + it.currency.lowercase() }
        .toList()

fun amountFacts(text: String): Map<String, String> {
    val amounts = moneyAmounts(text)

    val first = amounts.firstOrNull() ?: return totalFacts(arithmeticTotals(text))
    val values = amounts.map { it.value }.distinct()
    return buildMap {
        put(META_ENTITY_AMOUNT, first.value)
        put(META_ENTITY_AMOUNT_CURRENCY, first.currency)

        put(META_ENTITY_AMOUNT + META_SOURCE_SUFFIX, Provenance.OCR.wire)

        put(META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX, EvidenceClass.SEMANTIC.name.lowercase())
        if (values.size > 1) put(META_ENTITY_AMOUNT + META_MORE_SUFFIX, altValue(values))
    }
}

private fun totalFacts(totals: List<String>): Map<String, String> {
    val first = totals.firstOrNull() ?: return emptyMap()
    return buildMap {
        put(META_ENTITY_AMOUNT, first)

        put(META_ENTITY_AMOUNT + META_SOURCE_SUFFIX, Provenance.OCR.wire)
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

private const val H_SPACE = "[ \\t\\u00A0]"

private const val AMOUNT_NUMBER =
    "(?:\\d{1,3}(?:$H_SPACE\\d{3})+|\\d{1,$AMOUNT_MAX_DIGITS})(?:[.,]\\d{1,2})?"

private const val AMOUNT_LEFT = "(?<![\\d.,])(?<![\\d.,]$H_SPACE)"
private const val AMOUNT_RIGHT = "(?![\\d])(?![.,:]\\d)(?!$H_SPACE\\d)"

private val AMOUNT_SHAPED = Regex(
    "(?iu)(?:" +
        "(?<![\\p{L}])($CURRENCY_ALT)\\)?[ \\t\\u00A0]*\\n?[ \\t\\u00A0]*" +
        AMOUNT_LEFT + "($AMOUNT_NUMBER)" + AMOUNT_RIGHT +
        "|" +
        AMOUNT_LEFT + "($AMOUNT_NUMBER)" + AMOUNT_RIGHT +
        "[\\s\\u00A0]{0,2}($CURRENCY_ALT)(?![\\p{L}])" +
        ")",
)
