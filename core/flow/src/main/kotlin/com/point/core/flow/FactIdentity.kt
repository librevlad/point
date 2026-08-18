package com.point.core.flow

/**
 * Одно это знание или два разных — единственный ответ на весь Point (#1109, #1119, #1122).
 *
 * Вопрос «то же самое?» задавался в трёх местах и получал три разных ответа. Разбор текста
 * считал разными строки, различающиеся хоть символом, и заводил вторую запись; слияние знания
 * считало их спором и показывало «или:»; узлы схлопывались по календарному дню, но только у
 * дат. Отсюда один адрес дважды («ЕВГЕНІИВНА М. ПАВЛОГРАД…» рядом с «М. ПАВЛОГРАД…»), одна
 * ссылка дважды и один день в трёх строках.
 *
 * Здесь различаются три случая, и различие принципиальное (решение владельца 18.08.2026):
 *
 * - **прочтения одного факта** — одно знание, разногласие остаётся в `.alt`;
 * - **разные сущности того же вида** — два телефона в тексте остаются двумя знаниями;
 * - **тот же факт от другого исполнителя** — одно значение и два пути к нему.
 *
 * Победителя эта функция не выбирает: она отвечает только на вопрос тождества.
 */
fun sameFact(
    key: String,
    left: String,
    right: String,
    region: String = PhoneNumbers.DEFAULT_REGION,
): Boolean {
    val a = left.trim()
    val b = right.trim()
    if (a.isEmpty() || b.isEmpty()) return false
    if (normConsensus(a) == normConsensus(b)) return true

    // Номер судит библиотека, а не текст (#932): `067 636 05 60`, `+380676360560`
    // и `0676360560` — одно знание.
    if (key == META_ENTITY_PHONE) return PhoneNumbers.same(a, b, region)

    // Один день — одно знание (#660, решение владельца): «26.04.2026» и «26.04.2026 20:04»
    // говорят про один день, а 16-е и 18-е — про разные, и спор между ними настоящий.
    if (key == META_ENTITY_PREFIX + "date") {
        val dayA = humanDayOf(a) ?: return false
        return dayA == humanDayOf(b)
    }

    return oneInsideOther(a, b)
}

/**
 * Прочтение с прилипшим соседом — то же знание, а не второе (#1122).
 *
 * На накладной адрес прочитан дважды: «М. ПАВЛОГРАД, ВУЛ. КОДАЦЬКА, 39.» и он же с затянутым
 * отчеством получателя. Человеку показывали два места, которых на бумаге одно.
 *
 * Цифры внутри цифр так не судятся: «39» внутри «1839» — другое число, а не тот же номер.
 */
private fun oneInsideOther(left: String, right: String): Boolean {
    val a = bones(left)
    val b = bones(right)
    if (a.length < MIN_SHARED || b.length < MIN_SHARED) return false
    val short = if (a.length <= b.length) a else b
    val long = if (a.length <= b.length) b else a
    if (short.none(Char::isLetter)) return false
    if (!long.contains(short)) return false
    return short.length.toDouble() / long.length >= SHARED_SHARE
}

/** Короче — совпадение случайно: общий кусок должен быть словами, а не парой букв. */
private const val MIN_SHARED = 8

/** Насколько прочтения обязаны совпадать, чтобы считаться одним фактом. */
private const val SHARED_SHARE = 0.6

/**
 * Какое из двух прочтений одного факта информативнее.
 *
 * Не «правдоподобнее» и не «сильнее»: побеждает то, внутри которого целиком лежит второе, —
 * «26.04.2026 20:04» против «26.04.2026». Ничьё преимущество не выводится из того, кто
 * прочитал и когда: выбор исполнителя знанием не является.
 */
fun fullerReading(known: String, fresh: String): String {
    val a = bones(known)
    val b = bones(fresh)
    return if (b.length > a.length && b.contains(a)) fresh else known
}

/**
 * Что от значения остаётся, если снять запись: только буквы и цифры.
 *
 * Тождество считается по ним, а не по общей нормализации знания: та сворачивает числа под
 * свои правила и «26.04.2026» перестаёт лежать внутри «26.04.2026 20:04».
 */
private fun bones(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()
