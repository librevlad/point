package com.point.core.flow

/**
 * Итог чтения документа по страницам — одно правило на телефон и компьютер (#1254).
 *
 * Правило «сколько страниц прочитано → в каком состоянии вопрос чтения» было написано
 * дважды: в `:executors` и в `:desktop`. Одно обещание человеку жило в двух файлах, которые
 * никто не сверял, и правка порога на одной стороне молча оставила бы вторую с прежним
 * поведением.
 *
 * Сорвавшаяся страница и пустая — разные вещи, и до этой правки обе считались пустыми
 * (#1255): отказ сервиса на всех страницах человек читал как «Не разобрал текст ни на одной
 * странице» — утверждение о документе вместо правды «сервис не ответил». Сорвавшееся
 * исследование не переводит знание в «не нашлось» (CLAUDE.md, «Investigation State»),
 * поэтому у срыва состояния вопроса нет вовсе.
 */
data class PagesRead(

    /** Слова человеку об этом шаге. */
    val said: String,

    /**
     * Состояние вопроса чтения; `null` — прочитать не вышло, и вопрос остаётся нетронутым:
     * знания о том, есть ли в документе текст, добыто не было.
     */
    val state: InvestigationState?,
)

/**
 * @param total сколько страниц в документе
 * @param readable на скольких страницах нашёлся текст
 * @param broken сколько страниц сорвалось — движок или сервис до текста не дошёл
 * @param brokenSaid чем сорвалось — словами того слоя, который это видел
 */
fun pagesRead(total: Int, readable: Int, broken: Int, brokenSaid: String? = null): PagesRead = when {

    readable > 0 -> PagesRead(
        pagesReadSaid(readable, total) + brokenNote(broken, brokenSaid),
        if (readable == total) InvestigationState.FOUND else InvestigationState.INSUFFICIENTLY_INVESTIGATED,
    )

    broken > 0 -> PagesRead(brokenSaid ?: READ_NOT_NOW, state = null)

    else -> PagesRead(NO_TEXT_IN_DOCUMENT, InvestigationState.NOT_FOUND)
}

/** Прочитано — знание о документе, а не о страницах: текст ложится на сам документ. */
private fun pagesReadSaid(readable: Int, total: Int): String =
    "Прочитано страниц: $readable из $total — текст у документа"

/**
 * Часть страниц сорвалась — и человек об этом слышит (#1255). Без этой приписки «1 из 10»
 * читается как «на остальных девяти текста нет», хотя их никто не прочёл.
 */
private fun brokenNote(broken: Int, brokenSaid: String?): String =
    if (broken <= 0) "" else " · не прочиталось страниц: $broken" + brokenSaid?.let { " — $it" }.orEmpty()

/**
 * Страницы прочитаны, и текста на них нет — это знание, а не сбой (Конституция §13). Так же
 * отвечает чтение одиночного снимка: «не нашлось» — ответ на заданный вопрос, и второй раз
 * платить за него человек не должен.
 */
const val NO_TEXT_IN_DOCUMENT = "В документе не нашлось текста"
