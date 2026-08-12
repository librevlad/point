package com.point.core.flow

import kotlinx.coroutines.launch

interface DropInbox {

    /**
     * Открыть ящик приёма — или сказать, почему не вышло (#729).
     *
     * Прежде на всё возвращался `null`, и экран печатал единственную фразу про связь: «нет
     * сети», «устройство не в аккаунте» и «открытых ссылок слишком много» звучали одинаково.
     * Человек шёл проверять Wi-Fi там, где чинить нужно другое, — а сервер в это время
     * присылал готовый человеческий текст, и текст выбрасывался.
     */
    suspend fun open(): DropOpen

    suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait

    /**
     * «Файл дошёл» — сервер отдаёт его из ящика только после этого (#598-соседнее).
     *
     * Отдельный шаг, а не часть `await`: подтверждение стирает файл на сервере навсегда, и
     * посылал его чужой человек — прислать заново он не сможет. Пока объект не создан у нас,
     * подтверждать нечего: лучше получить тот же файл дважды, чем не получить ни разу.
     */
    suspend fun ack(box: DropInboxBox, fileId: String)

    /**
     * Ящик больше не нужен: файл получен и подтверждён либо человек ушёл, не дождавшись.
     *
     * Без этого дверь оставалась открытой до суточной уборки, и пять открытий экрана
     * «Принять файл» за день выбирали весь предел (#729).
     */
    suspend fun close(box: DropInboxBox) = Unit
}

/** Чем кончилась попытка открыть ящик: ссылкой или названной причиной (#729). */
sealed interface DropOpen {

    data class Opened(val box: DropInboxBox) : DropOpen

    data class Refused(val reason: String) : DropOpen
}

/**
 * Отказ словами сервера, а не одной фразой на все беды (#729).
 *
 * Упёрлись в предел ссылок — человеку сказано и что делать: сами ссылки перестают
 * действовать через сутки, ждать до утра не нужно.
 */
fun dropOpenRefusal(status: Int, serverMessage: String?, online: Boolean): String {
    if (!online) return NO_NETWORK_TEXT
    val said = serverMessage?.trim().orEmpty()
    return when {
        status == TOO_MANY_INBOXES && said.isNotEmpty() -> "$said. $OLD_LINKS_EXPIRE"
        status == TOO_MANY_INBOXES -> "Открытых ссылок слишком много. $OLD_LINKS_EXPIRE"
        said.isNotEmpty() -> said
        status <= 0 -> NO_SERVER_TEXT
        else -> NO_SERVER_TEXT
    }
}

private const val TOO_MANY_INBOXES = 507

private const val OLD_LINKS_EXPIRE = "Прежние ссылки перестают действовать через сутки"

/** Связь есть, а сервер не ответил — это не про Wi-Fi человека. */
const val NO_SERVER_TEXT = "Сервер Point не ответил. Попробуйте ещё раз"

/**
 * Три беды, которые раньше говорили чужим именем (#797, решение владельца 11.08.2026: «дать
 * каждой беде имя»).
 *
 * Живой прогон: приём файла отвечал «Сервер Point не ответил» при живом сервере и телефоне в
 * круге устройств — и по этой фразе нельзя было понять ни человеку, что чинить, ни нам, какая
 * из веток сработала.
 */
const val NO_SERVER_ADDRESS_TEXT = "Point не знает, к какому серверу идти"

const val NOT_IN_ACCOUNT_TEXT = "Устройство не в аккаунте. Войдите в настройках"

const val ODD_ANSWER_TEXT = "Сервер ответил непонятным — ссылку выдать не вышло"

const val NO_LINK_TEXT = "Ящик открыт, а ссылку на него собрать не вышло"

sealed interface DropWait {

    data class Arrived(val arrival: DropArrival) : DropWait

    data object Empty : DropWait

    data class Failed(val reason: String) : DropWait
}

fun receiveWaitStatus(failures: Int): String = when {
    failures < 1 -> "Ждём файл…"
    failures < 3 -> "Связь пропала — пробую снова…"
    else -> "Нет связи с сервером Point. Ссылка не потерялась: файл придёт, когда связь вернётся."
}

data class DropInboxBox(val id: String, val link: String)

data class DropArrival(val path: String, val name: String, val mime: String, val fileId: String = "")

fun dropInboxId(random: ByteArray): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random)

fun isDropInboxId(id: String): Boolean =
    id.length in 22..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

fun dropFileName(raw: String): String = safeFileName(raw, ifBlank = "файл")

fun dropInboxLink(relayUrl: String, id: String): String? {
    val base = relayUrl.trim().trimEnd('/')
    if (base.isBlank() || !isDropInboxId(id)) return null
    return "$base/u/$id"
}

/**
 * Сказать серверу «ящик больше не нужен» так, чтобы это пережило уход экрана (#729).
 *
 * Область жизни экрана к этому моменту уже отменена, а закрыть дверь нужно обязательно:
 * иначе предел в пять ссылок выберется обычным использованием.
 */
fun closeInBackground(inbox: DropInbox, box: DropInboxBox) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        .launch { runCatching { inbox.close(box) } }
}
