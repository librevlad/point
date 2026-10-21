package com.point.core.flow

import com.point.core.model.tr

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

val SIGN_IN_TITLE: String get() = tr("Ваши устройства увидят друг друга")

val SIGN_IN_ACTION: String get() = tr("Войти через Google")

fun signInWaitingLine(code: String): String =
    if (code.isBlank()) tr("Подтвердите вход в браузере") else tr("Подтвердите вход в браузере · код {0}", code)

val MY_DEVICES_TITLE: String get() = tr("Мои устройства")

fun deviceKindLabel(kind: DeviceKind): String = when (kind) {
    DeviceKind.PHONE -> tr("Телефон")
    DeviceKind.PC -> tr("Компьютер")
}

/**
 * [checkedNow] — сервер отвечал только что, и «на связи» — проверенный факт. Когда круг
 * показывается по памяти (#1076), утверждать «на связи» нельзя: связь не проверялась.
 * «Меньше часа назад» при этом остаётся правдой — последняя связь и впрямь была тогда.
 */
fun lastSeenLabel(lastSeenMillis: Long?, now: Long, checkedNow: Boolean = true): String {
    if (lastSeenMillis == null) return tr("ещё ни разу не выходило на связь")
    val ago = now - lastSeenMillis
    return when {
        ago < LIVE_MS -> if (checkedNow) tr("на связи") else tr("меньше часа назад")
        ago < 60 * 60_000L -> tr("меньше часа назад")
        ago < 24 * 60 * 60_000L -> tr("сегодня")
        ago < 48 * 60 * 60_000L -> tr("вчера")
        else -> tr("{0} дн. назад", ago / (24 * 60 * 60_000L))
    }
}

private const val LIVE_MS = 2 * 60_000L

fun accountRefusal(status: Int?): SignIn.Refused = when (status) {
    null -> SignIn.Refused(
        what = tr("До сервера Point не дозвониться"),
        fix = tr("Проверьте интернет и попробуйте ещё раз — ничего не потеряно."),
    )
    401, 403 -> SignIn.Refused(
        what = tr("Вход не подтверждён"),
        fix = tr("Откройте страницу входа заново и завершите вход в браузере."),
    )
    404, 410 -> SignIn.Refused(
        what = tr("Вход просрочен"),
        fix = tr("Страница входа живёт пять минут. Нажмите «Войти через Google» ещё раз."),
    )
    429 -> SignIn.Refused(
        what = tr("Сервер Point просит подождать"),
        fix = tr("Слишком много попыток подряд. Повторите через минуту."),
    )
    else -> SignIn.Refused(
        what = tr("Сервер Point ответил отказом ({0})", status),
        fix = tr("Попробуйте ещё раз; если повторится — сервер сейчас не в порядке."),
    )
}

val ACCOUNT_REVOKED: SignIn.Refused = SignIn.Refused(
    what = tr("Это устройство отключили от аккаунта"),
    fix = tr("Войдите снова, чтобы вернуть его в круг."),
)

/**
 * Вторая строка устройства в круге — одна на телефон и компьютер (#891).
 *
 * У «этого устройства» время последней связи не значит ничего: оно здесь, в руках. Раньше
 * строка всё равно писала «это устройство · Компьютер · меньше часа назад» и в узком окне
 * переносилась на три строки ради факта, который не нужен.
 */
fun deviceLine(device: CircleDevice, now: Long, checkedNow: Boolean = true): String = if (device.self) {
    tr("это устройство · ") + deviceKindLabel(device.kind)
} else {
    deviceKindLabel(device.kind) + " · " + lastSeenLabel(device.lastSeenMillis, now, checkedNow)
}

/** Значок вида — из общей таблицы, чтобы устройство выглядело одинаково везде. */
fun deviceIconKey(kind: DeviceKind): String = when (kind) {
    DeviceKind.PHONE -> "phone"
    DeviceKind.PC -> "pc"
}

/** Зачем вообще вход: без этой строки экран просил действие, не назвав причины. */
val SIGN_IN_WHY: String get() =
    tr("Войдите в один аккаунт на телефоне и на компьютере — и объект сможет переехать с одного " +
        "на другой. Для работы на одном устройстве вход не нужен.")

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
    SOURCE_CAMERA -> 1
    "voice" -> 2
    "location" -> 3
    "receive" -> 4
    else -> 9
}

/**
 * Камера — та же одна дверь, каким бы шагом её ни открыли (#1042).
 *
 * Имя источника нужно не только списку «с чего начать»: незавершённый шаг может попросить
 * именно снимок («Снять ещё страницу»), и приложение по этому имени открывает ту же камеру,
 * а не заводит вторую. Отсюда и константа: одно имя на оба места.
 */
const val SOURCE_CAMERA = "camera"
