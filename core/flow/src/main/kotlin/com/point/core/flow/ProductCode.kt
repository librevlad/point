package com.point.core.flow

/**
 * Настоящий ли это код товара (#940).
 *
 * На фотографии автомобиля Point показал «✓ Штрихкод 13821702». Штрихкода там нет — это
 * автомобиль. Сканер кодов ищет полосы в любом контрастном узоре, и на решётке радиатора,
 * тени или буквах номера он их находит: у ITF и Code 128 проверить прочитанное нечем, они
 * читаются как есть.
 *
 * У кодов товара — EAN и UPC — есть контрольная цифра, и она считается по самому коду. Это
 * та же проверка, которой Point уже пользуется для номеров отправлений: прочитанное сходится
 * само с собой или не сходится вовсе.
 *
 * Придуманный факт хуже отсутствующего: он стоит на экране галочкой, рядом с настоящей датой
 * съёмки, и человек не может отличить одно от другого.
 */
fun productCodeChecks(digits: String): Boolean {
    val code = digits.filter(Char::isDigit)
    if (code.length != digits.length) return false
    if (code.length !in PRODUCT_CODE_LENGTHS) return false

    // Контрольная цифра последняя, вес чередуется 1 и 3, считая справа.
    val body = code.dropLast(1).reversed()
    val sum = body.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
    val check = (10 - sum % 10) % 10
    return check == code.last() - '0'
}

/** Длины кодов товара: EAN-8, UPC-E, UPC-A, EAN-13. */
private val PRODUCT_CODE_LENGTHS = setOf(8, 12, 13)
