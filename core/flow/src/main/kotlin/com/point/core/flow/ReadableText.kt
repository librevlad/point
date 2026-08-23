package com.point.core.flow

/**
 * Читаем ли текст, извлечённый из документа (#933).
 *
 * У части PDF внутри своя раскладка шрифта: кириллица лежит под латинскими кодами, и
 * текстовый слой отдаётся мусором вроде `ToeapucrBo 3 o6MexeHop eignoeiganbHicrlo`. Так
 * устроены очень многие украинские бухгалтерские документы — счета, накладные, акты, то есть
 * ровно тот корпус, ради которого Point и нужен.
 *
 * Point этого не замечал: клал мусор текстом объекта и писал сверху «ПОНЯЛ», показывая
 * телефон и дату, выведенные из бессмыслицы. Худший вид отказа — тот, что выглядит успехом.
 *
 * Проверка дешёвая и без модели: у настоящего текста есть слова. Слово — это буквы подряд, в
 * которых бывают гласные; у подменённой раскладки слова рассыпаются в смесь букв и цифр,
 * гласные встают невозможными сочетаниями, а доля коротких обрывков зашкаливает.
 */
object ReadableText {

    /**
     * Похож ли текст на прочитанный.
     *
     * Короткие куски не судим: в двух словах не видно ничего, а ошибиться на подписи из
     * трёх букв легко. Молчание лучше ложного приговора.
     */
    fun readable(text: String): Boolean {
        // Таблица чисел, коды, показания — это данные, а не проза. Судить их не за что.
        val letters = text.count(Char::isLetter)
        if (letters * 100 < text.length * ENOUGH_LETTERS_PERCENT) return true

        val words = wordsOf(text)
        if (words.size < ENOUGH_WORDS) return true
        return words.count(::looksLikeWord) * 100 / words.size >= ENOUGH_PERCENT
    }

    fun unreadable(text: String): Boolean = !readable(text)

    private fun wordsOf(text: String): List<String> =
        text.split(*SEPARATORS).map { it.trim(*TRIM) }.filter { it.length >= 2 }

    /**
     * Слово ли это.
     *
     * Настоящее слово — только буквы одного письма, и в нём есть гласная. Смесь латиницы с
     * кириллицей (`Eniqgxtp`), цифры внутри (`e.qPnov`) и цепочки без единой гласной
     * (`BaxraxoorpxMyBaq` — гласные есть, а письмо смешано) выдают подмену раскладки.
     */
    private fun looksLikeWord(word: String): Boolean {
        if (word.any(Char::isDigit)) return false
        if (!word.all { it.isLetter() }) return false

        val cyrillic = word.count { it in 'а'..'я' || it in 'А'..'Я' || it in EXTRA_CYRILLIC }
        val latin = word.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (cyrillic > 0 && latin > 0) return false

        // Заглавная посреди слова — подпись подменённой раскладки: `cKnaAaHHR`,
        // `BaxraxoorpxMyBaq`, `flocraqanbHHK`. Человек пишет слово строчными, с большой
        // буквы или целиком прописными, но не вперемешку.
        if (word != word.lowercase() && word != word.uppercase() && !capitalizedOnly(word)) return false

        return word.any { it.lowercaseChar() in VOWELS }
    }

    private fun capitalizedOnly(word: String): Boolean =
        word.first().isUpperCase() && word.drop(1) == word.drop(1).lowercase()

    private const val ENOUGH_WORDS = 8

    /** Ниже этой доли букв текст — данные, а не проза, и читаемость к нему неприменима. */
    private const val ENOUGH_LETTERS_PERCENT = 25

    /** Доля настоящих слов, ниже которой текст считается нечитаемым. */
    private const val ENOUGH_PERCENT = 45

    private val SEPARATORS = charArrayOf(' ', '\n', '\r', '\t')
    private val TRIM = charArrayOf(',', '.', ':', ';', '!', '?', '"', '\'', '(', ')', '«', '»', '—', '-')
    private const val EXTRA_CYRILLIC = "ёЁіІїЇєЄґҐ"
    private const val VOWELS = "аеёиоуыэюяіїєaeiouy"
}

/**
 * Достаётся ли текст этого PDF из самого файла (#933, #995).
 *
 * Один смысл на обеих поверхностях: у телефона это исследование `pdf-image-shape`, у
 * компьютера — вопрос при приёме. Правило у них обязано быть одно, иначе тот же документ
 * на компьютере «скан», а на телефоне не скан, и двери ему рисуются разные — а «на той
 * стороне это тот же объект» (ADR-0001 §20).
 *
 * Слоя нет — читать нечего. Слой есть, но нечитаем (своя раскладка шрифта) — читать его тоже
 * нечего: «извлечённый» текст будет мусором. И то и другое значит одно: документ читается
 * страницами, а не файлом.
 *
 * [layer] — `null`, когда файл не открылся вовсе: это сбой операции, а не знание о документе.
 */
fun pdfLayerUnusable(layer: String?): Boolean {
    val text = layer ?: return false
    return text.isBlank() || ReadableText.unreadable(text)
}
