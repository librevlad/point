package com.point.source

/** Чего не хватает источнику из того, что он просил. Уже выданное не спрашивается повторно. */
fun missingPermissions(required: List<String>, granted: Set<String>): List<String> =
    required.filterNot { it in granted }

/**
 * Чем кончился запрос разрешения (#455).
 *
 * Два отказа выглядят одинаково — окно закрылось, доступа нет, — но означают разное, и разница
 * решает, есть ли у человека путь дальше. Пока Point их не различал, тот, кто однажды выбрал
 * «больше не спрашивать», получал один и тот же тост при каждом тапе: система отказывала
 * мгновенно, окно даже не показывалось, и понять, что решение теперь живёт в системных
 * настройках, было неоткуда.
 */
enum class PermissionOutcome {
    /** Дали всё, что просили, — источник начинает работу. */
    GRANTED,

    /** Отказали сейчас. Система спросит снова, поэтому путь прежний: тапнуть ещё раз. */
    DENIED,

    /** «Больше не спрашивать»: окна больше не будет, включить доступ можно только в настройках. */
    BLOCKED,
}

/**
 * Что означает ответ системы на запрос разрешения (#455).
 *
 * [willAskAgain] — это `shouldShowRequestPermissionRationale`: сразу после отказа он говорит
 * «объясни и спроси ещё раз», то есть окно вернётся. Если после отказа он отвечает «нет» —
 * спрашивать больше не будут никогда, и молчаливое закрытие экрана здесь и есть тупик.
 */
fun permissionOutcome(
    result: Map<String, Boolean>,
    willAskAgain: (String) -> Boolean,
): PermissionOutcome {
    val denied = result.filterValues { !it }.keys
    return when {
        denied.isEmpty() -> PermissionOutcome.GRANTED
        // Хватит одного закрытого навсегда: согласия по остальным не сдвинут источник с места.
        denied.any { !willAskAgain(it) } -> PermissionOutcome.BLOCKED
        else -> PermissionOutcome.DENIED
    }
}
