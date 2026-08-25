package com.point.core.flow

import com.point.core.model.Provenance
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

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
 * Пометка валюты стоит **при** числе: знак — с любой его стороны, буквы — после числа.
 * Какие это буквы, судья формы не спрашивает: «1200 kr», «1200 Kč», «1200 Ft» и «1200 лв» —
 * такие же числа, как «$2.18», и никакой список валют не вправе выбрасывать их из знания.
 * Буквы **перед** числом валютой не считаются: там пишут заголовок, а не деньги, — и «TAX1»,
 * «BAR 5,00» и «TOP 5» суммой не становятся.
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
 * Итог среди названных сумм (#1059, решение владельца): подписанный «итого», а если подписи
 * нет — наибольшее из чисел.
 *
 * Спрашивают это правило двое — правило чтения страницы [amountFacts] и судья улик, когда улик
 * у прочтений вышло поровну, — и спрашивают **одно и то же**: подпись ищется в тех же строках
 * страницы, что и там. Прежде судья среди равных брал просто наибольшее, и на чеке со строками
 * «TOTAL SAVINGS $5.00» и «CASH TOTAL $2.18» одна страница давала два ответа: правило — 2.18,
 * судья — 5.00, число, которого человек не платил никому.
 *
 * `page` — прочитанный текст страницы; его нет (пусто) — подпись искать негде, остаётся
 * величина. Прочтение судьи несёт валюту в себе — «$2.18», — поэтому здесь она читается из
 * самой записи.
 */
fun mainAmount(values: List<String>, page: String = ""): String? {
    if (values.isEmpty()) return null
    return mainOf(values.map { MoneyAmount(it, currencyMark(it).orEmpty()) }, page.lines()).value
}

/** Главная сумма: подписанная «итого» — раньше величины; остальные остаются в «ещё» (#1059). */
private fun mainOf(amounts: List<MoneyAmount>, lines: List<String>): MoneyAmount =
    amounts.filter { labelledTotal(lines, it.value, head = true) }
        .ifEmpty { amounts.filter { labelledTotal(lines, it.value, head = false) } }
        .ifEmpty { amounts }
        .let(::biggest)

/**
 * Наибольшая из сумм — итог, когда подпись его не назвала.
 *
 * Числа разных валют несравнимы: 5 € и 5 $ — не одно и то же, и большего среди них нет.
 * Тогда итог не выбирается вовсе и остаётся первое названное, как было до правила. Валюта,
 * которую документ не назвал, ничему не противоречит: на чеке она стоит раз на строку, а то
 * и одна на весь лист.
 *
 * Валюты сравниваются по тому, что они называют, а не по тому, как записаны: «₴» и «грн» —
 * одна и та же гривня, «¥1200» и «1200 JPY» — одна и та же иена. Прежде здесь стояло само
 * написание пометки, и квитанция со знаком в шапке и словом в строках молча выключала правило
 * величины.
 */
private fun biggest(amounts: List<MoneyAmount>): MoneyAmount {
    if (amounts.size < 2) return amounts.first()
    val named = amounts.map { it.currency }.filter { it.isNotBlank() }.map(::currencyCodes)
    val common = named.reduceOrNull { one, other -> one intersect other }.orEmpty()
    if (named.isNotEmpty() && common.isEmpty()) return amounts.first()
    return amounts.maxBy { amountValue(it.value) ?: BigDecimal.ZERO }
}

/** Валютная пометка при числе: знак Unicode, код ISO или слово; пометки нет — `null`. */
private fun currencyMark(value: String): String? =
    CURRENCY_FOUND.find(value)?.value?.lowercase()

/**
 * Какие валюты называет пометка (#1059).
 *
 * Знак, код и местное слово — три записи одного и того же, и сравнивать их написанием нельзя.
 * Что чем пишут, знает ISO: у каждого кода спрашиваем знак — по-английски, без языка и на
 * языке человека. Пометка называет не обязательно одну валюту («$» — доллар двух десятков
 * стран), поэтому ответ здесь множество: одной валютой две пометки считаются, когда у них
 * есть общая. Пометки, которой ISO не знает, никто не переписывает — она сама себе валюта.
 */
private fun currencyCodes(mark: String): Set<String> {
    val named = mark.trim().trimEnd('.').lowercase()
    return CURRENCY_OF_MARK[named] ?: setOf(named)
}

/**
 * Пометка → валюты, которые ею пишут (#1059). Собирается один раз и лениво: спрашивать ISO
 * стоит миллисекунд, а на первом экране сумм не читают вовсе.
 *
 * Что не всякую местную запись ISO называет по-английски — знает [CURRENCY_NAMES]: оттуда
 * сюда приходят гривня, рубль и злотый. Валюту, чей местный знак не назвали ни ISO, ни этот
 * список, переписывать некому: она остаётся сама себе валютой, и правило величины на такой
 * паре просто молчит, как молчало до #1059.
 */
private val CURRENCY_OF_MARK: Map<String, Set<String>> by lazy {
    val marks = HashMap<String, MutableSet<String>>()
    fun name(mark: String?, code: String) {
        val named = mark?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (named.isNotEmpty()) marks.getOrPut(named) { mutableSetOf() } += code
    }
    Currency.getAvailableCurrencies().forEach { currency ->
        name(currency.currencyCode, currency.currencyCode)
        listOf(Locale.ENGLISH, Locale.ROOT, Locale.getDefault()).forEach {
            name(currency.getSymbol(it), currency.currencyCode)
        }
    }
    CURRENCY_NAMES.forEach { (named, code) -> name(named, code) }
    marks
}

/**
 * Подписана ли эта сумма как итог документа (#1059, решение владельца).
 *
 * Подпись стоит в той же строке или в строке-заголовке над числом. Осматриваются **все**
 * строки, где стоит именно это число, а не первая встреченная: на чеке с одним товаром цена,
 * подытог и итог — одно и то же число, и первый раз оно встречается задолго до строки
 * «TOTAL». Прежде дальше первой такой строки правило не смотрело, подписанных сумм не
 * находило вовсе — и главной суммой вставали наличные, протянутые кассиру.
 *
 * `head` — подпись назвала итог сама («TOTAL», «CASH TOTAL»); иначе за подписью стоит слово,
 * говорящее, чего именно итог, и это уже другая величина: «TOTAL SAVINGS» — сколько
 * сэкономлено, «TOTAL TAX» — налог, «TOTAL ITEMS» — штуки. Прямая подпись сильнее такой,
 * а такая — сильнее величины: чек, где итог назван только «TOTAL DUE», всё равно подписан.
 */
private fun labelledTotal(lines: List<String>, value: String, head: Boolean): Boolean =
    lines.indices.any { at ->
        standsIn(lines[at], value) &&
            (namesTotal(lines[at], head) || namedAbove(lines, at, head))
    }

/**
 * Строка над числом — подпись колонки, только если своих чисел в ней нет: «Всього, грн»
 * сверху и число снизу — одна подпись, разорванная переносом.
 */
private fun namedAbove(lines: List<String>, at: Int, head: Boolean): Boolean {
    val above = lines.getOrNull(at - 1) ?: return false
    return moneyAmounts(above).isEmpty() && namesTotal(above, head)
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

/**
 * Называет ли строка итог документа, и называет ли его сама (#1059).
 *
 * Подпись — слово целиком: «SUBTOTAL» итога не объявляет, «CASH TOTAL» объявляет. Чего
 * именно итог — говорят слова после подписи: за «TOTAL SAVINGS» стоит размер скидки, за
 * «TOTAL TAX» — налог, и человеку под галочкой досталось бы число, которого он не платил
 * никому. Валюта другой величины не называет: «Всього, грн» — та же подпись итога.
 */
private fun namesTotal(line: String, head: Boolean, markers: List<String> = TOTAL_MARKERS): Boolean {
    val words = line.replace(NOT_LETTER, " ").trim().split(' ').filter(String::isNotEmpty)
    val lower = words.map(String::lowercase)
    return markers.any { marker ->
        val marked = marker.split(' ')
        val at = (lower.size - marked.size downTo 0)
            .firstOrNull { lower.subList(it, it + marked.size) == marked }
        when {
            at == null -> false
            !head -> true
            else -> words.drop(at + marked.size).all(::currencyWord)
        }
    }
}

/** Названа ли этим словом валюта: код ISO пишут заглавными, местное имя — как придётся. */
private fun currencyWord(word: String): Boolean =
    word in CURRENCY_CODES || word.lowercase() in CURRENCY_NAMES.keys

private val NOT_LETTER = Regex("[^\\p{L}]+")

/** Слова, которыми документ называет свой итог (#1059). */
internal val TOTAL_MARKERS = listOf("total", "итого", "всього", "до сплати", "к оплате")

/**
 * Слова, которыми документ называет свою сумму: итог и просто «сумма» (#1059).
 *
 * Их же берёт судья улик (`FIELD_MARKERS`), и читает он их **той же** [namesAmount], что и
 * правило страницы. Прежде судья метил слово поодиночке: «TOTAL» он видел, стоящего рядом
 * «SAVINGS» — нет, — и строка скидки получала ту же улику подписи, что строка итога. Улик
 * выходило поровну, спор решала величина, и под галочкой у человека вставало 5.00 вместо 2.18.
 */
internal val AMOUNT_MARKERS = TOTAL_MARKERS + listOf("сума", "сумма", "amount")

/**
 * Называет ли подпись сумму документа — и называет ли её саму (#1059). Один и тот же вопрос
 * задают правило страницы и судья улик, поэтому и правило на него одно.
 */
internal fun namesAmount(label: String): Boolean = namesTotal(label, head = true, AMOUNT_MARKERS)

fun moneyAmounts(text: String): List<MoneyAmount> =
    AMOUNT_SHAPED.findAll(text)
        .map { m ->

            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            val currency = m.groupValues[1]
                .ifEmpty { m.groupValues[4] }
                .ifEmpty { m.groupValues[5] }
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
    val main = mainOf(amounts, text.lines())
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

/**
 * Коды валют — ISO 4217, а не список в этом файле (#1059). Прежде валют было шестнадцать,
 * и «1200 JPY» суммой не считалось вовсе: на пути модели такое значение переставало быть
 * знанием, хотя число в нём — обычное число. Коды знает JVM, и они не устаревают вместе
 * с файлом.
 */
private val CURRENCY_CODES: Set<String> =
    Currency.getAvailableCurrencies().mapTo(sortedSetOf()) { it.currencyCode }

/**
 * Как валюту пишут там, где её код не пишут, и что это за валюта (#1059).
 *
 * По-английски ISO называет знак доллара, фунта, иены, рупии, воны и шекеля само — а гривню,
 * рубль и злотый пишет кодом. Знак и слово этих трёх и стоят здесь: «500 грн» и «₴500» — одна
 * и та же гривня, и правило величины на квитанции со знаком в шапке и словом в строках должно
 * работать. Остальные валюты мира сюда переписывать не надо.
 */
private val CURRENCY_NAMES = mapOf(
    "₴" to "UAH", "грн." to "UAH", "грн" to "UAH",
    "₽" to "RUB", "руб." to "RUB", "руб" to "RUB",
    "zł" to "PLN",
)

// Знаки валют регулярному выражению уже известны как `\p{Sc}` — сюда идут только имена словом.
private val CURRENCY_ALT = CURRENCY_NAMES.keys
    .filter { it.any(Char::isLetter) }
    .sortedByDescending { it.length }
    .joinToString("|") { Regex.escape(it) }

/**
 * Валютная пометка **при** числе: любой знак валюты Unicode (`\p{Sc}`) или местное имя. Список
 * знаков закрытым быть не может — иен, рупий и злотых в мире больше, чем в этом файле.
 */
private val CURRENCY_NEAR = "(?:\\p{Sc}|$CURRENCY_ALT)"

/**
 * Код валюты — только **после** числа (#1059).
 *
 * Код читается заглавными, а заглавными пишут и заголовки: «TOP» — тонганская паанга, «ALL» —
 * албанский лек, «TRY», «CUP», «PEN» — тоже коды. Стоя перед числом, любое такое слово делало
 * суммой обычный английский заголовок: на скриншоте статьи под галочкой вставало «Сумма 5»,
 * а выдуманная валюта «TOP» рядом с настоящей молча выключала правило величины. После числа
 * заголовка не бывает — там пишут деньги: «1200 JPY», «2,50 EUR».
 */
private val CURRENCY_CODE = "(?-i:" + CURRENCY_CODES.joinToString("|") + ")"

private val CURRENCY_AFTER = "(?:$CURRENCY_NEAR|$CURRENCY_CODE)"

private val CURRENCY_FOUND = Regex("(?iu)$CURRENCY_AFTER")

private const val H_SPACE = "[ \\t\\u00A0]"

private const val AMOUNT_NUMBER =
    "(?:\\d{1,3}(?:$H_SPACE\\d{3})+|\\d{1,$AMOUNT_MAX_DIGITS})(?:[.,]\\d{1,2})?"

private const val AMOUNT_LEFT = "(?<![\\d.,])(?<![\\d.,]$H_SPACE)"
private const val AMOUNT_RIGHT = "(?![\\d])(?![.,:]\\d)(?!$H_SPACE\\d)"

private val AMOUNT_SHAPED = Regex(
    "(?iu)(?:" +
        "(?<![\\p{L}])($CURRENCY_NEAR)\\)?[ \\t\\u00A0]*\\n?[ \\t\\u00A0]*" +
        AMOUNT_LEFT + "($AMOUNT_NUMBER)" + AMOUNT_RIGHT +
        "|" +
        AMOUNT_LEFT + "($AMOUNT_NUMBER)" + AMOUNT_RIGHT +

        // Знак валюты вплотную к следующему числу принадлежит ему, а не предыдущему: в
        // «TAX1 $0.18» доллар — при 18 копейках, и «1» из подписи суммой не становится.
        // Знак и местное имя стоят и строкой ниже — так печатают колонку квитанции; код же
        // читается только в строке своего числа, иначе «…2026 \n ALL 3…» с обычной страницы
        // даёт «2026 албанских леков».
        "(?:[\\s\\u00A0]{0,2}($CURRENCY_NEAR)|[ \\t\\u00A0]{0,2}($CURRENCY_CODE))(?![\\p{L}\\d])" +
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
 * Пометка валюты после числа у судьи формы (#1059): знак или короткие буквы.
 *
 * Здесь значение уже названо суммой — и вопрос один: число ли это. Какими буквами написана
 * валюта, спрашивать нечем и незачем: «kr», «Kč», «Ft», «лв» — деньги ровно настолько же,
 * насколько «JPY» и «грн», а список валют, решающий за них, отнимал у человека знание вовсе.
 * Длиной пометка и отличается от слова: валюту пишут тремя-четырьмя буквами.
 */
private const val CURRENCY_TAIL = "(?:\\p{Sc}|\\p{L}{1,4}\\.?)"

/**
 * Значение суммы целиком (#1059): число, при нём — знак и валюта, больше ничего. Знак пишут
 * и до валюты, и после неё: «-$2.18» — такой же минус, как «$-2.18». Перед числом стоит знак
 * валюты или её местное имя, после — любая короткая пометка.
 */
private val AMOUNT_VALUE = Regex(
    "(?iu)$AMOUNT_SIGN(?:$CURRENCY_NEAR$H_SPACE*)?$AMOUNT_SIGN$AMOUNT_NUMBER_ANY" +
        "(?:$H_SPACE*$CURRENCY_TAIL)?",
)
