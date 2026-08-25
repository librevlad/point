package com.point.core.flow

import com.point.core.model.ObjectKind

/**
 * Своими словами о том, почему снимок не прочитался.
 *
 * Раньше причина от платформы уходила прямо на экран, и человек читал
 * «не удалось прочитать страницу — decode failed» (#686). Техническая причина
 * остаётся в журнале, человеку достаётся одно понятное предложение.
 *
 * Отказ говорит о том объекте, который человек принёс (#1033): битый PDF раньше объясняли
 * словами про картинку — «повреждён или это не изображение» — при подписи «PDF» на том же
 * экране. Вид объекта обязателен у каждого вызова: умолчание снова подсунуло бы PDF чужие
 * слова молча.
 */
fun readerFailure(reason: String?, kind: ObjectKind): String = when (troubleOf(reason)) {
    ReaderTrouble.BROKEN -> brokenFile(kind)
    ReaderTrouble.NO_PAGES -> EMPTY_DOCUMENT
    ReaderTrouble.TOO_SLOW -> READ_TOO_SLOW
    ReaderTrouble.TOO_BIG -> READ_TOO_BIG
    ReaderTrouble.NOT_NOW -> READ_NOT_NOW
}

/** Чтение оборвалось по времени — про попытку, а не про файл человека (#684/#685). */
const val READ_TOO_SLOW = "Чтение заняло слишком долго и оборвалось"

/** Снимок не влез в чтение здесь и сейчас — тоже про попытку, а не про сам снимок. */
const val READ_TOO_BIG = "Снимок слишком большой, чтобы его прочитать"

/**
 * Не вышло сейчас — про попытку, а не про файл человека (#1258).
 *
 * Сюда попадает всё, чего словарь не опознал: не завёлся движок («engine init failed»),
 * внутренняя ошибка чтения («error: OutOfMemoryError»), незнакомый сигнал. Раньше все они
 * шли в «Файл не открылся — он повреждён»: человек шёл переснимать или удалял «битую»
 * фотографию, хотя сломался наш движок. Обе функции файла теперь читают один разбор, и
 * «виноват файл» звучит ровно там, где [readerFailureIsFatal] отвечает «да».
 */
const val READ_NOT_NOW = "Прочитать сейчас не вышло — попробуйте ещё раз"

/**
 * Технический сигнал ридера: страниц в документе нет вовсе (#570). Человеку его переводит
 * [readerFailure] — сам этот текст наружу не выходит.
 */
const val READER_NO_PAGES = "pdf has no pages"

/**
 * Технический сигнал ридера: байты не разобрались в снимок (#1258).
 *
 * Раньше это место звалось пустым сигналом — `readerFailure(null)`, — и словарь отвечал на
 * него «файл повреждён», а годность объекта на тот же вход отвечала «дело не в объекте». Обе
 * функции читают один разбор, поэтому и вход у них один: кто видел неразобранные байты, тот
 * их и называет. Сам сигнал человеку не показывается — его переводит [readerFailure].
 */
const val READER_NOT_DECODED = "decode failed"

/**
 * Только это действительно говорит о самом объекте, а не о попытке прочитать его сейчас
 * (#684/#685): байты не декодируются, это не изображение вовсе, страниц нет ни одной.
 * Долгое чтение, слишком большой снимок, не запустившийся движок — про исполнение здесь и
 * сейчас, а не про годность объекта, и не должны навсегда закрывать путь наружу.
 */
fun readerFailureIsFatal(reason: String?): Boolean = when (troubleOf(reason)) {
    ReaderTrouble.BROKEN, ReaderTrouble.NO_PAGES -> true
    else -> false
}

/**
 * Тот же вопрос, что и [readerFailureIsFatal], но по уже сказанным человеку словам (#1101).
 *
 * Технический сигнал живёт ровно один вызов: с объектом остаётся фраза, которой ему
 * объяснили негодность (`META_UNUSABLE_REASON`). Дальше по ней и приходится решать — при
 * выборе дверей сигнала уже нет, а вопрос тот же: дело в самом файле или в попытке
 * прочитать его сейчас. Второго разбора здесь нет: [readerFailure] — единственный, кто эти
 * слова произносит, и «про попытку» их ровно три. Совпадение обоих ответов на всём словаре
 * закреплено тестом.
 *
 * Незнакомая фраза — не наша: так говорят о содержимом пустой файл и обломок архива, и это
 * знание первого захода, а не исход операции.
 */
fun saidFailureIsFatal(said: String?): Boolean =
    !said.isNullOrBlank() && said !in ONLY_ABOUT_THE_ATTEMPT

private val ONLY_ABOUT_THE_ATTEMPT = setOf(READ_NOT_NOW, READ_TOO_SLOW, READ_TOO_BIG)

/**
 * Что именно случилось при чтении — один разбор сигнала на оба ответа (#1258): и на слова
 * человеку, и на вопрос «дело в самом объекте?». Пока разборов было два, один и тот же
 * сигнал получал «файл повреждён» от первого и «это не про объект» от второго.
 */
private enum class ReaderTrouble { BROKEN, NO_PAGES, TOO_SLOW, TOO_BIG, NOT_NOW }

private fun troubleOf(reason: String?): ReaderTrouble {

    // Отдельной ветки у пустого сигнала нет, и это решение: молчание не закрывает путь наружу
    // (#684/#685), а раз так — и «повреждён» про него говорить нельзя. Кто действительно видел
    // неразобранные байты, называет это [READER_NOT_DECODED] (#1258).
    val said = reason.orEmpty().lowercase()
    return when {
        NO_PAGES.any { it in said } -> ReaderTrouble.NO_PAGES
        NOT_AN_IMAGE.any { it in said } -> ReaderTrouble.BROKEN
        TOO_SLOW.any { it in said } -> ReaderTrouble.TOO_SLOW
        looksLikeTooBig(said) -> ReaderTrouble.TOO_BIG
        else -> ReaderTrouble.NOT_NOW
    }
}

/**
 * Словарь отказа по виду объекта — один на всех потребителей (#1033). Вид без своего слова
 * получает только факт поломки: догадка «это не …» уместна лишь там, где известно, чем файл
 * должен был быть.
 */
private fun brokenFile(kind: ObjectKind): String = when (kind) {
    ObjectKind.PDF -> "Файл не открылся — он повреждён или это не PDF"
    ObjectKind.IMAGE -> "Файл не открылся — он повреждён или это не изображение"
    else -> "Файл не открылся — он повреждён"
}

private const val EMPTY_DOCUMENT = "В документе нет ни одной страницы"

private val NOT_AN_IMAGE = listOf("decode", "not an image", "unsupported", "corrupt", "malformed")

private val NO_PAGES = listOf("no pages")

private val TOO_SLOW = listOf("timeout", "timed out", "deadline")
