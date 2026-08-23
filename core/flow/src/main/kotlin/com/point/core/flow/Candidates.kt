package com.point.core.flow

data class FieldCandidate(

    val text: String,

    val ids: List<String> = emptyList(),

    /** Имя человека при значении — пара «номер | имя» из CONTACT-строк (#653). */
    val person: String? = null,

    /** Строка документа вокруг значения — подпись при нём, а не оно само (#782). */
    val line: String? = null,
)

const val MAX_FIELD_CANDIDATES = 3

enum class EvidenceClass { SEMANTIC, GEOMETRIC, LEXICAL, STRUCTURAL, ARITHMETIC }

const val CONFIRMED_CLASSES = 2

const val META_EVIDENCE_SUFFIX = ".ev"

const val META_SOURCE_SUFFIX = ".src"

const val META_BLOCKED_SUFFIX = ".blocked"

/**
 * Строка документа, из которой вычитано значение — подпись при нём, а не оно само (#782).
 * В спор, в «ещё» и в тождество объекта подпись не входит: это контекст значения.
 */
const val META_LINE_SUFFIX = ".line"

fun isAnnotationKey(key: String): Boolean =
    key.endsWith(META_ALT_SUFFIX) || key.endsWith(META_MORE_SUFFIX) ||
        key.endsWith(META_EVIDENCE_SUFFIX) || key.endsWith(META_SOURCE_SUFFIX) ||
        key.endsWith(META_BLOCKED_SUFFIX) || key.endsWith(META_LINE_SUFFIX) ||
        key.endsWith(META_ACTOR_SUFFIX) ||

        // Принадлежность факта стороне (#1176): связь при знании, а не второе знание.
        // Только при знании объекта: `exec.of` — родословная просьбы, а не хозяин факта.
        isBelongingKey(key) ||

        // Сырые якоря канонического структурного узла (#1176): подпись места, не факт.
        key.endsWith(META_ANCHOR_ROW_SUFFIX) || key.endsWith(META_ANCHOR_COL_SUFFIX)

fun semanticFits(key: String, value: String): Boolean? {
    val digits = value.count(Char::isDigit)
    return when (key) {

        META_ENTITY_TRACK -> digits in 13..14 || S10_SHAPED.matches(value.trim().uppercase().replace(" ", ""))
        // В телефоне не бывает слов. Модель дала чистый «067 636 05 60», но метки указывали
        // и на имя рядом, и заземление по странице возвращало склейку «Тарасенко Світлана
        // Сергіївна 067 636 05 60» — она и вставала значением телефона (охота 11.08.2026).
        // Телефон судит библиотека: номер либо существует, либо нет (#801). Самодельное
        // «от десяти до тринадцати цифр» пропускало номер карты и не отличало украинский
        // номер от американского.
        META_ENTITY_PREFIX + "phone" -> PhoneNumbers.exists(value)
        META_ENTITY_PREFIX + "email" -> value.contains('@') && value.substringAfter('@').contains('.')
        META_ENTITY_PREFIX + "card" -> digits in 15..19 || looksLikeIban(value)
        // Дата — это дата, а не всё, где есть цифра и точка: «4.» из нумерации пункта
        // вставало отдельной находкой рядом с настоящими днями (#782).
        META_ENTITY_PREFIX + "date" -> holdsDate(value)

        META_ENTITY_METER -> meterDigitsFit(value)

        META_ENTITY_AMOUNT -> amountDigitsFit(value)
        META_ENTITY_RECEIPT -> receiptNumberShaped(value)

        META_ENTITY_GEO -> geoPoints(value).isNotEmpty()

        META_ENTITY_ADDRESS -> if (addressForm(value.trim()) != null) true else null
        else -> null
    }
}

/**
 * Кандидат становится знанием, только заслужив смысл (#1139).
 *
 * Между «нашли строку» и «это факт объекта» не было ни нормализации, ни проверки: значение
 * попадало в знание как есть, и побеждало то, что встретилось в тексте первым. Отсюда
 * телефон из номера дома, немецкий номер из товарного штрихкода, ссылка из «com.ua» и
 * скобки внутри значения.
 *
 * Здесь одна воронка на все входы: снять обёртку, спросить форму, вернуть значение или
 * `null`. Проверка своя у каждого вида знания и там, где правила нет, молчит (`semanticFits`
 * отвечает `null`) — жизнь богаче правила, и молчание пропускает.
 *
 * Тождеством это не занимается: «то же самое?» по-прежнему один `sameFact` (#1136).
 */
fun factCandidate(key: String, raw: String): String? {
    val value = unwrapped(raw)
    if (value.isEmpty()) return null
    return if (semanticFits(key, value) == false) null else value
}

/**
 * Знаки вокруг значения — запись, а не само значение (#1064).
 *
 * Номер в круглых скобках давал вторую находку, и скобки уезжали внутрь факта. Снимается
 * только парная обёртка целиком: `(067) 123-45-67` обёрткой не является и остаётся собой.
 */
fun unwrapped(raw: String): String {
    var value = raw.trim()
    while (value.length > 2 && WRAPPERS.any { value.first() == it.first && value.last() == it.second }) {
        value = value.substring(1, value.length - 1).trim()
    }
    return value
}

private val WRAPPERS: List<Pair<Char, Char>> = listOf(
    '(' to ')', '[' to ']', '{' to '}', '«' to '»', '“' to '”', '‘' to '’',
)

fun formEvidence(key: String, value: String): Set<EvidenceClass> = buildSet {
    if (semanticFits(key, value) == true) add(EvidenceClass.SEMANTIC)
    if (key == META_ENTITY_TRACK && s10CheckDigitValid(value) == true) add(EvidenceClass.ARITHMETIC)
}

fun s10CheckDigitValid(value: String): Boolean? {
    val v = value.trim().uppercase().replace(" ", "")
    if (!S10_SHAPED.matches(v)) return null
    val digits = v.substring(2, 11).map { it - '0' }
    val sum = S10_WEIGHTS.indices.sumOf { digits[it] * S10_WEIGHTS[it] }
    val check = when (val c = 11 - sum % 11) {
        10 -> 0
        11 -> 5
        else -> c
    }
    return check == digits[8]
}

private val S10_SHAPED = Regex("""[A-Z]{2}\d{9}[A-Z]{2}""")
private val S10_WEIGHTS = intArrayOf(8, 6, 4, 2, 3, 5, 9, 7)

fun bareIndexId(id: String): String = id.replace(INDEX_ATTRS, "")

private val INDEX_ATTRS = Regex("""\s+rule=\S*$""")

fun AtomLayer.fieldEvidence(
    key: String,
    candidate: FieldCandidate,
    ruleMarks: Map<String, List<String>> = ruleEvidence(),
): Set<EvidenceClass> {
    val classes = mutableSetOf<EvidenceClass>()
    val resolved = resolve(AtomAddress.ByIds(candidate.ids.map(::bareIndexId)))

    classes += formEvidence(key, candidate.text)
    val ruleFits = key == META_ENTITY_TRACK && resolved.atoms.isNotEmpty() &&
        resolved.atoms.all { "track-shaped" in ruleMarks[it.id].orEmpty() } &&
        resolved.text.count(Char::isDigit) == WAYBILL_DIGITS
    if (ruleFits) classes += EvidenceClass.SEMANTIC

    if (resolved.atoms.isEmpty()) return classes

    if (resolved.droppedIds.isEmpty() && !resolved.disjoint) classes += EvidenceClass.STRUCTURAL

    val markers = FIELD_MARKERS[key].orEmpty()
    if (markers.isEmpty()) return classes
    val valueIds = resolved.atoms.mapTo(mutableSetOf()) { it.id }
    val named = atoms.filter { it.text.isNotBlank() }
    val pageLines = lines(named)
    val lineOf = HashMap<String, Int>()
    pageLines.forEachIndexed { i, line -> line.forEach { lineOf[it.id] = i } }
    val valueLines = resolved.atoms.mapNotNull { lineOf[it.id] }.toSet()
    if (valueLines.isEmpty()) return classes

    fun isMarker(atom: Atom) = atom.text.trim().trimEnd(':', '.').lowercase() in markers

    val valueBox = resolved.atoms.map { it.box }.reduce(Box::union)
    valueLines.forEach { li ->
        val runs = cellRuns(pageLines[li])
        val valueRunIdx = runs.indices.filter { i -> runs[i].any { it.id in valueIds } }
        runs.forEachIndexed { ri, run ->
            val holdsValue = ri in valueRunIdx
            val marker = run.any { it.id !in valueIds && isMarker(it) }
            if (marker && holdsValue) classes += EvidenceClass.LEXICAL
            if (marker && !holdsValue && valueRunIdx.any { kotlin.math.abs(it - ri) == 1 }) {
                classes += EvidenceClass.GEOMETRIC
            }
        }
        pageLines.getOrNull(li - 1).orEmpty().forEach { atom ->
            val overlapsX = atom.box.left <= valueBox.right && valueBox.left <= atom.box.right
            val closeAbove = valueBox.top - atom.box.bottom <=
                maxOf(atom.box.height, valueBox.height) * LABEL_GAP_HEIGHTS
            if (overlapsX && closeAbove && isMarker(atom)) classes += EvidenceClass.GEOMETRIC
        }
    }
    return classes
}

internal val WORDY = Regex("""\p{L}{3,}""")

private const val LABEL_GAP_HEIGHTS = 2f

internal val FIELD_MARKERS: Map<String, List<String>> = mapOf(
    META_ENTITY_TRACK to listOf(
        "ттн", "трек", "трек-номер", "накладна", "накладная", "відправлення", "отправление",
        "waybill", "tracking",
    ),
    META_ENTITY_PREFIX + "phone" to listOf("тел", "телефон", "phone", "моб", "mobile"),
    META_ENTITY_PREFIX + "email" to listOf("email", "e-mail", "почта", "пошта", "мейл"),
    META_ENTITY_PREFIX + "date" to listOf("дата", "date", "від", "от"),
    META_ENTITY_PREFIX + "address" to listOf(
        "адреса", "адрес", "address", "відділення", "отделение",
    ),
    META_ENTITY_PREFIX + "card" to listOf("карта", "картка", "card", "iban", "рахунок", "счёт", "счет"),

    META_ENTITY_METER to listOf(
        "показання", "показания", "показник", "лічильник", "личильник", "счётчик", "счетчик",
        "meter", "reading", "квт·ч", "квт*ч", "квтч", "квт·год", "м³", "м3", "kwh", "гкал",
    ),
    META_ENTITY_GEO to listOf(
        "координати", "координаты", "coordinates", "gps", "широта", "довгота", "долгота",
    ),

    META_ENTITY_AMOUNT to listOf(
        "сума", "сумма", "amount", "total", "всього", "итого", "до сплати", "к оплате",
        "грн", "₴", "uah", "$", "usd", "€", "eur",
    ),

    META_ENTITY_RECEIPT to listOf("квитанція", "квитанции", "квитанция", "квитанцію", "receipt"),
    META_ENTITY_SUBJECT to listOf("тема", "subject", "тему"),
)
