package com.point

sealed interface Incoming {

    data class Single(val uri: String, val mime: String) : Incoming

    data class Many(val uris: List<String>) : Incoming

    data class Body(val text: String) : Incoming
}

const val DEFAULT_MIME = "application/octet-stream"

/**
 * Текст, в котором текста нет, объектом не становится (#1096).
 *
 * Пробелы — такой же пустой вход, как и отсутствующий текст: для человека между ними разницы
 * нет, и решать это правило обязано одно место, а не каждая дверь по-своему. Текст к тому же
 * приезжает не только строкой — отправитель вправе положить размеченный CharSequence.
 */
fun bodyOf(text: CharSequence?): Incoming.Body? =
    text?.toString()?.takeUnless(String::isBlank)?.let(Incoming::Body)

fun incomingOf(
    action: String?,
    type: String?,
    data: String?,
    stream: String?,
    text: CharSequence?,
    streams: List<String> = emptyList(),
): Incoming? = when (action) {
    "android.intent.action.SEND" ->
        if (stream != null) Incoming.Single(stream, type ?: DEFAULT_MIME) else bodyOf(text)

    "android.intent.action.SEND_MULTIPLE" ->
        streams.takeIf { it.isNotEmpty() }?.let(Incoming::Many)

    "android.intent.action.VIEW" ->
        data?.let { Incoming.Single(it, type ?: DEFAULT_MIME) }

    else -> null
}
