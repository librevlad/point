package com.point.core.flow

interface DropInbox {

    suspend fun open(): DropInboxBox?

    suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait

    /**
     * «Файл дошёл» — сервер отдаёт его из ящика только после этого (#598-соседнее).
     *
     * Отдельный шаг, а не часть `await`: подтверждение стирает файл на сервере навсегда, и
     * посылал его чужой человек — прислать заново он не сможет. Пока объект не создан у нас,
     * подтверждать нечего: лучше получить тот же файл дважды, чем не получить ни разу.
     */
    suspend fun ack(box: DropInboxBox, fileId: String)
}

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

fun dropFileName(raw: String): String {
    val base = raw.replace('\\', '/').substringAfterLast('/').trim().trimStart('.')
    val safe = base.filter { it.code >= 0x20 && it !in "\\/:*?\"<>|" }.take(120)
    return safe.ifBlank { "файл" }
}

fun dropInboxLink(relayUrl: String, id: String): String? {
    val base = relayUrl.trim().trimEnd('/')
    if (base.isBlank() || !isDropInboxId(id)) return null
    return "$base/u/$id"
}
