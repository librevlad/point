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
fun readerFailure(reason: String?, kind: ObjectKind): String {
    val said = reason.orEmpty().lowercase()
    return when {
        said.isBlank() -> brokenFile(kind)
        NO_PAGES.any { it in said } -> EMPTY_DOCUMENT
        NOT_AN_IMAGE.any { it in said } -> brokenFile(kind)
        TOO_SLOW.any { it in said } -> "Чтение заняло слишком долго и оборвалось"
        TOO_BIG.any { it in said } -> "Снимок слишком большой, чтобы его прочитать"
        else -> brokenFile(kind)
    }
}

/**
 * Технический сигнал ридера: страниц в документе нет вовсе (#570). Человеку его переводит
 * [readerFailure] — сам этот текст наружу не выходит.
 */
const val READER_NO_PAGES = "pdf has no pages"

/**
 * Только это действительно говорит о самом объекте, а не о попытке прочитать его сейчас
 * (#684/#685): байты не декодируются, это не изображение вовсе, страниц нет ни одной.
 * Долгое чтение, слишком большой снимок, не запустившийся движок — про исполнение здесь и
 * сейчас, а не про годность объекта, и не должны навсегда закрывать путь наружу.
 */
fun readerFailureIsFatal(reason: String?): Boolean {
    val said = reason.orEmpty().lowercase()
    return NOT_AN_IMAGE.any { it in said } || NO_PAGES.any { it in said }
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

private val TOO_BIG = listOf("too large", "too big", "size limit", "413")
