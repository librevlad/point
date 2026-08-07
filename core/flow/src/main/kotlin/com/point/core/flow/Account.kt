package com.point.core.flow

enum class DeviceKind { PHONE, PC }

data class PointAccount(
    val deviceId: String,
    val deviceToken: String,
    val email: String,
    val deviceName: String,
    val kind: DeviceKind,
)

data class CircleDevice(
    val id: String,
    val kind: DeviceKind,
    val name: String,
    val lastSeenMillis: Long? = null,
    val self: Boolean = false,

    val key: String = "",
)

sealed interface SignIn {

    data object SignedOut : SignIn

    data class Waiting(val loginId: String, val code: String, val url: String) : SignIn

    data class SignedIn(val account: PointAccount) : SignIn

    data class Refused(val what: String, val fix: String) : SignIn
}

data class LoginStart(
    val loginId: String,
    val claimToken: String,
    val code: String,
    val url: String,

    val handoff: Boolean = false,
)

sealed interface CircleAnswer {
    data class Circle(val devices: List<CircleDevice>) : CircleAnswer

    data object Unreachable : CircleAnswer

    data object Revoked : CircleAnswer
}

sealed interface LoginPoll {

    data object Pending : LoginPoll

    data object Silent : LoginPoll

    data class Ready(val account: PointAccount) : LoginPoll

    data class Refused(val what: String, val fix: String) : LoginPoll
}

data class PendingLogin(
    val loginId: String,
    val claimToken: String,
    val code: String,
    val url: String,
    val startedAtMillis: Long,
)

const val SIGN_IN_TITLE = "Ваши устройства увидят друг друга"

const val SIGN_IN_ACTION = "Войти через Google"

fun signInWaitingLine(code: String): String =
    if (code.isBlank()) "Подтвердите вход в браузере" else "Подтвердите вход в браузере · код $code"

const val MY_DEVICES_TITLE = "Мои устройства"

fun deviceKindLabel(kind: DeviceKind): String = when (kind) {
    DeviceKind.PHONE -> "Телефон"
    DeviceKind.PC -> "Компьютер"
}

fun lastSeenLabel(lastSeenMillis: Long?, now: Long): String {
    if (lastSeenMillis == null) return "ещё ни разу не выходило на связь"
    val ago = now - lastSeenMillis
    return when {
        ago < 0 -> "на связи"
        ago < LIVE_MS -> "на связи"
        ago < 60 * 60_000L -> "меньше часа назад"
        ago < 24 * 60 * 60_000L -> "сегодня"
        ago < 48 * 60 * 60_000L -> "вчера"
        else -> "${ago / (24 * 60 * 60_000L)} дн. назад"
    }
}

private const val LIVE_MS = 2 * 60_000L

fun accountRefusal(status: Int?): SignIn.Refused = when (status) {
    null -> SignIn.Refused(
        what = "До сервера Point не дозвониться",
        fix = "Проверьте интернет и попробуйте ещё раз — ничего не потеряно.",
    )
    401, 403 -> SignIn.Refused(
        what = "Вход не подтверждён",
        fix = "Откройте страницу входа заново и завершите вход в браузере.",
    )
    404, 410 -> SignIn.Refused(
        what = "Вход просрочен",
        fix = "Страница входа живёт пять минут. Нажмите «Войти через Google» ещё раз.",
    )
    429 -> SignIn.Refused(
        what = "Сервер Point просит подождать",
        fix = "Слишком много попыток подряд. Повторите через минуту.",
    )
    else -> SignIn.Refused(
        what = "Сервер Point ответил отказом ($status)",
        fix = "Попробуйте ещё раз; если повторится — сервер сейчас не в порядке.",
    )
}

val ACCOUNT_REVOKED: SignIn.Refused = SignIn.Refused(
    what = "Это устройство отключили от аккаунта",
    fix = "Войдите снова, чтобы вернуть его в круг.",
)
