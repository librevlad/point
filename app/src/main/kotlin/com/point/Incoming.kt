package com.point

/**
 * Что пришло в дверь Point.
 *
 * Разбор намеренно не знает ни одного Android-типа: дверь достаёт куски из своего `Intent`, а
 * решение «объект это или ничего» принимается здесь — и потому судится юнит-тестом, а не руками
 * на устройстве. Дверей у Point больше одной (#248), и договор у них общий: любой вход сводится
 * к ссылке с типом и уходит в `FlowViewModel.onShared`.
 */
sealed interface Incoming {
    /** Один объект по ссылке: шаринг файла и «Открыть с помощью» приходят сюда одинаково. */
    data class Single(val uri: String, val mime: String) : Incoming

    /** Несколько объектов сразу (мульти-шаринг). */
    data class Many(val uris: List<String>) : Incoming

    /** Текст пришёл телом intent, файла за ним нет — дверь сама положит его в scratch. */
    data class Body(val text: String) : Incoming
}

/** Тип, когда система его не назвала: врать конкретным типом нельзя, признаки соврут следом. */
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

    // «Открыть с помощью» (#249): объект лежит в data, а не в EXTRA_STREAM.
    "android.intent.action.VIEW" ->
        data?.let { Incoming.Single(it, type ?: DEFAULT_MIME) }

    else -> null
}
