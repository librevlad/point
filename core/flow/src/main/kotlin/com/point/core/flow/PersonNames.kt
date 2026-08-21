package com.point.core.flow

/**
 * Роль без правдоподобного имени — не человек (#654): модель, отвечая ролью на целый
 * текст или номер, не рождает «человека» из документа. Общее правило обеих сторон
 * протокола понимания (#653: пары «имя+номер» фильтруются им же).
 */
fun plausiblePersonName(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty() || t.length > 60) return false
    if (!t.any(Char::isLetter)) return false

    // Группа цифр — номер или сумма, не имя; одиночная цифра в слове — искажение
    // распознавания («1ваненко ван»), это ещё имя.
    if (Regex("""\d{2,}""").containsMatchIn(t)) return false
    if (t.count(Char::isDigit) > t.length / 5) return false

    // Смешанные алфавиты в одном слове — огрех чтения, не имя и не организация (#1032):
    // тот же судья, что у адреса, — иначе «РÉPUBLIOUEFRANCAISE» вставало выдавшей
    // документ организацией.
    if (hasMixedScriptWord(t)) return false
    return t.split(Regex("""\s+""")).size <= 5
}
