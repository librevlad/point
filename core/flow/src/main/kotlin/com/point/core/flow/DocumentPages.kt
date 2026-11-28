package com.point.core.flow

import com.point.core.model.tr

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
 * Исход чтения считается по просмотренным страницам, а не по страницам с текстом (карточка
 * «Не отвечать «Ничего не нашлось» по скану, прочитанному не целиком», 28.11.2026).
 *
 * Прежде «найдено» ставилось, только если текст нашёлся на каждой странице: чистый лист в
 * середине скана делал прочитанный целиком документ «2 из 3» и «исследовано недостаточно» —
 * неотличимо от страницы, до которой чтение не дошло. Пустая страница просмотрена, и о ней
 * известно ровно то, что надо: текста на ней нет.
 *
 * @param total сколько страниц в документе — в самом документе, а не дошедших до чтения
 * @param readable на скольких страницах документа есть текст — во всём прочтении, вместе с
 * прочитанными прежними заходами
 * @param broken сколько страниц сорвалось в этом заходе — движок или сервис до текста не дошёл
 * @param brokenSaid чем сорвалось — словами того слоя, который это видел
 * @param covered сколько страниц документа просмотрено до конца — с текстом или пустых — во
 * всём прочтении
 * @param newlyRead сколько страниц довёл до ответа этот заход: заход, не прочитавший ни одной,
 * знания не добыл, и его срыв — срыв операции, а не ответ о документе
 * @param stoppedAt на какой странице этот заход остановился, не дойдя до тех, что дальше:
 * растеризатор вправе нарисовать не все (`PdfRasterizer.rasterize`); `null` — нарисовано всё,
 * что просили
 */
fun pagesRead(
    total: Int,
    readable: Int,
    broken: Int,
    brokenSaid: String? = null,
    covered: Int = readable,
    newlyRead: Int = covered,
    stoppedAt: Int? = null,
): PagesRead = when {

    newlyRead == 0 && broken > 0 -> PagesRead(brokenSaid ?: READ_NOT_NOW, state = null)

    readable > 0 -> PagesRead(
        pagesReadSaid(covered, total) + stopNote(stoppedAt) + brokenNote(broken, brokenSaid),
        if (covered >= total) InvestigationState.FOUND else InvestigationState.INSUFFICIENTLY_INVESTIGATED,
    )

    // Пусто на прочитанных страницах — ещё не «в документе нет текста», если до части страниц
    // чтение не дошло. Приговор документу по его началу — тот же обман, что «100 из 100» у
    // скана на 150 страниц: вопрос закрылся бы «не нашлось», а хвост никто не смотрел
    // (`not investigated` ≠ `not found`). Поэтому — «исследовано недостаточно» и счёт вслух.
    covered < total -> PagesRead(
        tr("Прочитано страниц: {0} из {1} — текста на них нет", covered, total) +
            stopNote(stoppedAt) + brokenNote(broken, brokenSaid),
        InvestigationState.INSUFFICIENTLY_INVESTIGATED,
    )

    else -> PagesRead(NO_TEXT_IN_DOCUMENT, InvestigationState.NOT_FOUND)
}

/** Прочитано — знание о документе, а не о страницах: текст ложится на сам документ. */
private fun pagesReadSaid(covered: Int, total: Int): String =
    tr("Прочитано страниц: {0} из {1} — текст у документа", covered, total)

/**
 * Заход остановился раньше конца документа — и человек слышит где и чем продолжить (тот же
 * класс, что #1255). Без этой приписки «100 из 150» читается как «на остальных пятидесяти
 * текста нет», хотя до них не дошли: срез — предел одного захода, а не свойство документа.
 *
 * Прежде здесь стояло «дальше 100-й страницы Point не читает» — и это было правдой: дочитать
 * хвост было нечем. Теперь следующий заход есть ([ReadDocumentCapability]), и приписка зовёт
 * его по имени.
 */
private fun stopNote(stoppedAt: Int?): String =
    stoppedAt?.let { tr(" · страницы после {0}-й прочитает «Дочитать документ»", it) }.orEmpty()

/**
 * Часть страниц сорвалась — и человек об этом слышит (#1255). Без этой приписки «1 из 10»
 * читается как «на остальных девяти текста нет», хотя их никто не прочёл.
 */
private fun brokenNote(broken: Int, brokenSaid: String?): String =
    if (broken <= 0) "" else tr(" · не прочиталось страниц: {0}", broken) + brokenSaid?.let { " — $it" }.orEmpty()

/**
 * Страницы прочитаны, и текста на них нет — это знание, а не сбой (Конституция §13). Так же
 * отвечает чтение одиночного снимка: «не нашлось» — ответ на заданный вопрос, и второй раз
 * платить за него человек не должен.
 */
val NO_TEXT_IN_DOCUMENT: String get() = tr("В документе не нашлось текста")

/**
 * Какие страницы документа просмотрены до конца и сколько их в нём всего — то, что лежит в
 * [META_OCR_TEXT_COVERAGE] (карточка «Не отвечать «Ничего не нашлось» по скану, прочитанному не
 * целиком»).
 *
 * Номер — по документу, с единицы. Страница, которую читатель довёл до ответа, здесь есть —
 * с текстом она или пустая; сорвавшейся и не нарисованной нет.
 */
data class PageCoverage(val pages: Set<Int>, val total: Int) {

    /** Сколько страниц документа просмотрено. */
    val count: Int get() = pages.count { it in 1..total }

    /** Просмотрен ли документ целиком. */
    val complete: Boolean get() = total > 0 && count == total
}

/** Счёт просмотренных страниц словами знания: отрезки через запятую и счёт документа — `1-100,102/110`. */
fun pageCoverageWire(coverage: PageCoverage): String {
    val pages = coverage.pages.filter { it in 1..coverage.total }.sorted()
    val runs = mutableListOf<IntRange>()
    pages.forEach { page ->
        val last = runs.lastOrNull()
        if (last != null && last.last + 1 == page) runs[runs.lastIndex] = last.first..page else runs += page..page
    }
    val listed = runs.joinToString(",") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
    return "$listed/${coverage.total}"
}

/**
 * Счёт просмотренных страниц — или `null`, если документ о нём не рассказал.
 *
 * `null` — обычный случай, а не поломка: текст, прочитанный до того, как счёт стал знанием, его
 * не знает. Испорченная запись тоже даёт `null`, а не роняет чтение и поиск: без счёта они
 * поступают так же, как с прежним прочтением.
 */
fun pageCoverageOf(metadata: Map<String, String>): PageCoverage? {
    val wire = metadata[META_OCR_TEXT_COVERAGE]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val total = wire.substringAfterLast('/', "").trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    val pages = wire.substringBeforeLast('/', "")
        .split(',')
        .flatMap { run ->
            val from = run.substringBefore('-').trim().toIntOrNull()
            val to = run.substringAfter('-', run).trim().toIntOrNull()
            if (from == null || to == null || from < 1 || to < from) emptyList() else (from..minOf(to, total)).toList()
        }
        .toSet()
    return PageCoverage(pages, total)
}

/**
 * Какие страницы рисовать за один заход: страницы документа на [pageCount] страниц, кроме уже
 * прочитанных [except], по порядку — и не больше [atMost].
 *
 * Одно правило на телефон и компьютер: телефон бережёт память и время и рисует за заход не
 * больше сотни, компьютер — сколько попросили. Заход начинается с первой непросмотренной
 * страницы, а не с первой страницы документа: иначе длинный скан читался бы с начала каждый раз
 * и до хвоста не доходил никогда.
 *
 * Сотня — сотня непрочитанных, а не сотня подряд: страница, на которой читатель срывается
 * каждый раз, занимает в заходе одно место, а не держит на себе начало окна. Иначе скан на 250
 * страниц с вечно сорванной третьей рисовал бы 3–102 снова и снова, и страницы после 102-й не
 * читались бы никогда.
 */
fun pagesToDraw(except: Set<Int>, pageCount: Int, atMost: Int = Int.MAX_VALUE): List<Int> =
    (1..pageCount).asSequence().filter { it !in except }.take(atMost).toList()

/**
 * Чтение документа начато и до конца не доведено: часть страниц просмотрена, часть — нет.
 *
 * Пока это так, дверь чтения остаётся на месте и зовётся «Дочитать документ» (карточка «Не
 * отвечать «Ничего не нашлось» по скану, прочитанному не целиком»). Прежде дверь закрывалась,
 * как только у документа появлялся текст, — и непрочитанные страницы не читались уже никогда,
 * хотя слова самого чтения звали «попробуйте ещё раз».
 *
 * Прочтение, легшее до того, как счёт стал знанием, счёта не знает; недочитанным его называет
 * состояние вопроса чтения — «исследовано недостаточно» при уже лежащем тексте.
 */
fun readingToFinish(metadata: Map<String, String>, hasText: Boolean): Boolean {
    val coverage = pageCoverageOf(metadata)
    if (coverage != null) return !coverage.complete && coverage.count > 0
    return hasText &&
        investigationStateOf(metadata, KnownCapabilities.IMAGE_TEXT) == InvestigationState.INSUFFICIENTLY_INVESTIGATED
}

/** Чем склеены страницы в тексте документа: пустой строкой. */
internal const val PAGE_BREAK = "\n\n"

/**
 * Что уже прочитано у документа здесь — с чего продолжать следующий заход.
 *
 * @param texts текст каждой страницы, на которой он есть, — по номеру страницы в документе
 * @param covered какие страницы здесь просмотрены: с текстом и пустые — их следующий заход не
 * рисует и не читает
 */
internal class ReadSoFar(val texts: Map<Int, String>, val covered: Set<Int>)

/**
 * Прочитанное раньше, разложенное обратно по страницам, — или `null`, если продолжать нечего и
 * следующий заход читает документ с начала.
 *
 * Продолжать нечего, когда документ просмотрен целиком (новое чтение по просьбе человека —
 * чтение заново) или когда о прежнем прочтении не известно, где в нём страницы.
 *
 * Здесь лежит не всё, что прочитано, если текст доехал с другого устройства обрезком
 * ([META_OCR_TEXT_CUT]): страницы, которые начинаются за концом текста, сюда не доехали, а
 * страница, на которой он оборвался, доехала не целиком. Счёт просмотренного едет с тем
 * устройством целиком, и верить ему здесь о таких страницах нельзя — их читает следующий заход.
 *
 * [text] — то, что лежит по ссылке на текст; `null` — текста нет или он не открылся.
 */
internal fun readSoFar(metadata: Map<String, String>, text: String?): ReadSoFar? {
    val coverage = pageCoverageOf(metadata)
    if (coverage?.complete == true) return null
    val starts = pageStartsOf(metadata)
    val body = text.orEmpty()
    if (coverage == null && !readingToFinish(metadata, hasText = body.isNotBlank())) return null

    // Текст без границ страниц по страницам не разложить — склеить с новыми было бы не в чем.
    if (body.isNotBlank() && starts.isEmpty()) return null

    val inText = starts.filter { it.offset < body.length }
    val whole = if (body.isNotEmpty() && textCutShort(metadata, body.length)) inText.dropLast(1) else inText
    val texts = whole.mapIndexed { index, start ->
        val from = if (index == 0) 0 else start.offset
        val to = inText.getOrNull(index + 1)?.offset ?: body.length
        start.page to body.substring(from, to).removeSuffix(PAGE_BREAK)
    }.filter { (_, pageText) -> pageText.isNotBlank() }.toMap()

    val lost = starts.map { it.page }.toSet() - texts.keys
    val covered = (coverage?.pages ?: texts.keys) - lost
    return ReadSoFar(texts, covered)
}
