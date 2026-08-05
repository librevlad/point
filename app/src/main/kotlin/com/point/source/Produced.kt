package com.point.source

import com.point.core.flow.stampedObjectName
import com.point.core.flow.textObjectName

/**
 * Что добыл источник: ссылка, тип — и имя, если источник знает его лучше файловой системы (#533).
 *
 * [name] пустое там, где файл пришёл из чужих рук со своим именем («отчёт.pdf»): выдумывать имя
 * поверх настоящего значило бы стереть то, что человек уже знает об объекте. Оно заполняется ровно
 * там, где имя рождал сам Point и рождал машинно: текст из буфера, снятый кадр, запись, место.
 */
data class Produced(val uri: String, val mime: String, val name: String? = null)

/** Имя объекта, которое источник передаёт двери «Поделиться». Свой ключ, а не системный
 *  `EXTRA_TITLE`: чужое приложение кладёт туда что угодно, и подменять имя объекта извне нельзя. */
const val EXTRA_OBJECT_NAME = "com.point.source.OBJECT_NAME"

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
    !text.isNullOrBlank() -> Produced(textFile(text), "text/plain", textObjectName(text))
    else -> null
}

/**
 * Что родится из камеры.
 *
 * Файл создаётся ДО съёмки (камере нужно, куда писать), поэтому его существование ничего не
 * доказывает — доказывает размер. Отменённая съёмка оставляет нулевой файл, и объектом он не
 * становится: иначе человек получил бы пустую карточку вместо честной тишины.
 *
 * [epochMillis] — когда кадр снят (время файла), а не когда его разбирают: это единственное, что о
 * кадре известно до всякого распознавания, и из этого складывается его имя.
 */
fun captureToProduced(
    path: String,
    sizeBytes: Long,
    epochMillis: Long = System.currentTimeMillis(),
): Produced? =
    if (sizeBytes > 0) Produced(path, "image/jpeg", stampedObjectName("Снимок", epochMillis)) else null
