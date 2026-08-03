package com.point.source

/** Что добыл источник: ссылка и тип — ровно то, что умеет принять `FlowViewModel.onShared`. */
data class Produced(val uri: String, val mime: String)

/** Тип, когда система его не назвала. */
private const val UNKNOWN_MIME = "application/octet-stream"

/**
 * Что родится из буфера обмена.
 *
 * Файл побеждает текст: если в буфере лежит ссылка на файл, объектом становится он сам, а не его
 * текстовое представление. Пустота — не объект: пустой объект в работе хуже честного «в буфере
 * пусто».
 */
fun clipToProduced(
    text: String?,
    uri: String?,
    mime: String?,
    textFile: (String) -> String,
): Produced? = when {
    uri != null -> Produced(uri, mime ?: UNKNOWN_MIME)
    !text.isNullOrBlank() -> Produced(textFile(text), "text/plain")
    else -> null
}

/**
 * Что родится из камеры.
 *
 * Файл создаётся ДО съёмки (камере нужно, куда писать), поэтому его существование ничего не
 * доказывает — доказывает размер. Отменённая съёмка оставляет нулевой файл, и объектом он не
 * становится: иначе человек получил бы пустую карточку вместо честной тишины.
 */
fun captureToProduced(path: String, sizeBytes: Long): Produced? =
    if (sizeBytes > 0) Produced(path, "image/jpeg") else null
