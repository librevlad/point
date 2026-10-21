package com.point.core.flow

import com.point.core.model.CapabilityId

import com.point.core.model.tr
fun cloudDestination(
    capabilityId: CapabilityId,

    aiService: String? = null,
): String = when (capabilityId.value) {
    "drop-link" ->
        tr("Файл уедет на сервер Point и сутки будет открыт любому, у кого есть ссылка. " +
            "Потом ссылка перестанет работать.")

    "ocr", "ocr-cloud" ->
        tr("Снимок уйдёт на сервер распознавания и вернётся текстом.")

    else -> if (aiService != null) {
        tr("Объект уйдёт на сервер {0} и вернётся результатом.", aiService)
    } else {
        tr("Объект уйдёт на чужой сервер и вернётся результатом.")
    }
}

fun cloudScopeOf(capabilityId: CapabilityId): CloudScope = when (capabilityId.value) {
    "drop-link" -> CloudScope.PUBLIC_LINK
    else -> CloudScope.MODELS
}

fun cloudAskTitle(scope: CloudScope): String = when (scope) {
    CloudScope.PUBLIC_LINK -> tr("Выложить файл по ссылке?")
    CloudScope.MODELS -> tr("Отправить в облако?")
}

fun cloudAskConfirm(scope: CloudScope): String = when (scope) {
    CloudScope.PUBLIC_LINK -> tr("Выложить")
    CloudScope.MODELS -> tr("Разрешить")
}
