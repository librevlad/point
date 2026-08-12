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

/**
 * Вторая строка устройства в круге — одна на телефон и компьютер (#891).
 *
 * У «этого устройства» время последней связи не значит ничего: оно здесь, в руках. Раньше
 * строка всё равно писала «это устройство · Компьютер · меньше часа назад» и в узком окне
 * переносилась на три строки ради факта, который не нужен.
 */
fun deviceLine(device: CircleDevice, now: Long): String = if (device.self) {
    "это устройство · " + deviceKindLabel(device.kind)
} else {
    deviceKindLabel(device.kind) + " · " + lastSeenLabel(device.lastSeenMillis, now)
}

/** Значок вида — из общей таблицы, чтобы устройство выглядело одинаково везде. */
fun deviceIconKey(kind: DeviceKind): String = when (kind) {
    DeviceKind.PHONE -> "phone"
    DeviceKind.PC -> "pc"
}

/** Зачем вообще вход: без этой строки экран просил действие, не назвав причины. */
const val SIGN_IN_WHY: String =
    "Войдите в один аккаунт на телефоне и на компьютере — и объект сможет переехать с одного " +
        "на другой. Для работы на одном устройстве вход не нужен."

/**
 * Порядок источников на экране «с чего начать» (#895).
 *
 * Раньше список сортировался по алфавиту — и держался только на том, что названия начинались
 * с разных букв. С глагольным строем «Взять из буфера», «Взять место», «Записать голос»
 * алфавит перестал что-либо значить. Порядок теперь по пользе: сначала то, что человек
 * делает чаще и быстрее всего.
 */
fun sourceOrder(id: String): Int = when (id) {
    "clipboard" -> 0
    "camera" -> 1
    "voice" -> 2
    "location" -> 3
    "receive" -> 4
    else -> 9
}
