package com.point.core.flow

/**
 * Своими словами о том, почему снимок не прочитался.
 *
 * Раньше причина от платформы уходила прямо на экран, и человек читал
 * «не удалось прочитать страницу — decode failed» (#686). Техническая причина
 * остаётся в журнале, человеку достаётся одно понятное предложение.
 */
fun readerFailure(reason: String?): String {
    val said = reason.orEmpty().lowercase()
    return when {
        said.isBlank() -> BROKEN_FILE
        NOT_AN_IMAGE.any { it in said } -> BROKEN_FILE
        TOO_SLOW.any { it in said } -> "Чтение заняло слишком долго и оборвалось"
        TOO_BIG.any { it in said } -> "Снимок слишком большой, чтобы его прочитать"
        else -> BROKEN_FILE
    }
}

/**
 * Только это действительно говорит о самом объекте, а не о попытке прочитать его сейчас
 * (#684/#685): байты не декодируются, это не изображение вовсе. Долгое чтение, слишком
 * большой снимок, не запустившийся движок — про исполнение здесь и сейчас, а не про
 * годность объекта, и не должны навсегда закрывать путь наружу.
 */
fun readerFailureIsFatal(reason: String?): Boolean {
    val said = reason.orEmpty().lowercase()
    return NOT_AN_IMAGE.any { it in said }
}

private const val BROKEN_FILE = "Файл не открылся — он повреждён или это не изображение"

private val NOT_AN_IMAGE = listOf("decode", "not an image", "unsupported", "corrupt", "malformed")

private val TOO_SLOW = listOf("timeout", "timed out", "deadline")

private val TOO_BIG = listOf("too large", "too big", "size limit", "413")
