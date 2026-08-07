package com.point.core.flow

import com.point.core.model.CapabilityId

fun cloudDestination(
    capabilityId: CapabilityId,

    aiService: String? = null,
): String = when (capabilityId.value) {
    "drop-link" ->
        "Файл уедет на сервер Point и сутки будет открыт любому, у кого есть ссылка. " +
            "Потом ссылка перестанет работать."

    "ocr", "ocr-cloud" ->
        "Снимок уйдёт на сервер распознавания и вернётся текстом."

    else -> if (aiService != null) {
        "Объект уйдёт на сервер $aiService и вернётся результатом."
    } else {
        "Объект уйдёт на сервер AI-провайдера и вернётся результатом."
    }
}

fun cloudScopeOf(capabilityId: CapabilityId): CloudScope = when (capabilityId.value) {
    "drop-link" -> CloudScope.PUBLIC_LINK
    else -> CloudScope.MODELS
}

fun cloudAskTitle(scope: CloudScope): String = when (scope) {
    CloudScope.PUBLIC_LINK -> "Выложить файл по ссылке?"
    CloudScope.MODELS -> "Отправить в облако?"
}

fun cloudAskConfirm(scope: CloudScope): String = when (scope) {
    CloudScope.PUBLIC_LINK -> "Выложить"
    CloudScope.MODELS -> "Разрешить"
}
