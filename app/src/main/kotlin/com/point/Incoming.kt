package com.point

sealed interface Incoming {

    data class Single(val uri: String, val mime: String) : Incoming

    data class Many(val uris: List<String>) : Incoming

    data class Body(val text: String) : Incoming
}

const val DEFAULT_MIME = "application/octet-stream"

fun incomingOf(
    action: String?,
    type: String?,
    data: String?,
    stream: String?,
    text: String?,
    streams: List<String> = emptyList(),
): Incoming? = when (action) {
    "android.intent.action.SEND" -> when {
        stream != null -> Incoming.Single(stream, type ?: DEFAULT_MIME)
        !text.isNullOrEmpty() -> Incoming.Body(text)
        else -> null
    }

    "android.intent.action.SEND_MULTIPLE" ->
        streams.takeIf { it.isNotEmpty() }?.let(Incoming::Many)

    "android.intent.action.VIEW" ->
        data?.let { Incoming.Single(it, type ?: DEFAULT_MIME) }

    else -> null
}
