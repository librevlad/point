package com.point.core.flow

/**
 * Годится ли значение этому виду знания — по форме, а не по источнику.
 *
 * Одно место на все входы: и на то, что модель нашла впервые, и на то, что она же потом
 * исправила (#666). Правило, живущее в двух копиях, расходится на первой же правке.
 *
 * Правила намеренно бедные и односторонние: они умеют сказать «это точно не оно» и молчат
 * там, где жизнь богаче правила (координаты, показания счётчика). Молчание — «пропустить».
 */
fun factFits(key: String, value: String): Boolean {
    val text = value.trim()
    if (text.isEmpty()) return false

    // Отказ-фраза — не значение ни для какого поля (#656).
    if (startsWithRefusal(text)) return false

    return when (key.removePrefix(META_ENTITY_PREFIX)) {

        // Относительное слово и голое время — не дата (#659, #651); дата без цифр — тоже.
        "date" -> !relativeDayWord(text) && !bareClock(text) && semanticFits(key, text) != false

        // Арифметика и ноль — не сумма документа (#662).
        "amount" -> !looksLikeExpression(text) && !zeroAmount(text)

        // Форма IBAN — не трек: «UA79…» с квитанции становился готовым отслеживанием.
        // Слово — тем более: «квитанцію» и «№ 7 36ір» с кадров прогона вставали трек-номерами
        // и получали готовое «отследить» на пустом месте (#657).
        "track" -> !looksLikeIban(text) && semanticFits(key, text) != false

        "address" -> plausibleAddress(text)

        else -> true
    }
}
