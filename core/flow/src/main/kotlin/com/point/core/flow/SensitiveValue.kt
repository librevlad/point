package com.point.core.flow

fun maskedForScreen(key: String, value: String): String = when (key) {

    META_ENTITY_PREFIX + "card" -> maskedCard(value)
    else -> value
}

private fun maskedCard(value: String): String {
    val digits = value.filter(Char::isDigit)
    return if (digits.length <= CARD_TAIL) value else "•• " + digits.takeLast(CARD_TAIL)
}

private const val CARD_TAIL = 4
