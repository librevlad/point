package com.point.core.flow

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Приём файла по ссылке — один код на телефон и компьютер (#727).
 *
 * Решение владельца 10.08.2026: «общая реализация в core:flow». `DesktopDropLink` уже был
 * второй копией того же HTTP рядом с `RelayDropLink`; третья копия закрепила бы расхождение —
 * правку пришлось бы делать дважды, и однажды забыли бы. Ровно так и потерялся
 * `python-multipart` на сервере.
 *
 * Сеть спрашивается у устройства до выхода наружу (#690, #691): каждая сторона отвечает за
 * себя своим [NetworkAvailability], а сам разговор с сервером — общий.
 */
class HttpDropInbox(
    private val serverUrl: () -> String?,
    private val pass: () -> String?,
    private val network: NetworkAvailability = NetworkAvailability { true },
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,
) : DropInbox {

    override suspend fun open(): DropOpen {
        if (!network.isAvailable()) return DropOpen.Refused(dropOpenRefusal(0, null, online = false))

        // У каждой беды своё имя (#797, решение владельца 11.08.2026). Прежде все три ветки
        // этого метода печатали «Сервер Point не ответил» — и человек шёл проверять сервер,
        // который мог быть ни при чём, а найти настоящую причину было нечем.
        val address = serverUrl()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: return DropOpen.Refused(NO_SERVER_ADDRESS_TEXT)
        if (pass().isNullOrBlank()) return DropOpen.Refused(NOT_IN_ACCOUNT_TEXT)
        val base = address
        return runCatching {
            val c = connect("$base/u/open", "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            c.outputStream.close()
            val status = c.responseCode
            if (status !in 200..299) {

                // Сервер прислал человеческий текст — он и есть причина. Выбрасывать его,
                // чтобы напечатать «нет связи», значит отправить человека чинить не то (#729).
                val said = runCatching { c.errorStream?.readBytes()?.decodeToString() }.getOrNull()
                c.disconnect()
                return@runCatching DropOpen.Refused(
                    dropOpenRefusal(status, said?.let { parseJson(it).str("message") }, online = true),
                )
            }
            val body = c.inputStream.readBytes().decodeToString()
            c.disconnect()

            val answer = parseJson(body)
            val box = answer.str("box")?.takeIf { it.isNotBlank() }
                ?: return@runCatching DropOpen.Refused(ODD_ANSWER_TEXT)
            val link = answer.str("url")?.takeIf { it.isNotBlank() }
                ?: dropInboxLink(base, box) ?: return@runCatching DropOpen.Refused(NO_LINK_TEXT)
            DropOpen.Opened(DropInboxBox(box, link))
        }.getOrElse { DropOpen.Refused(NO_SERVER_TEXT) }
    }

    override suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait {
        if (!network.isAvailable()) return DropWait.Failed(NO_NETWORK_TEXT)
        val base = base() ?: return DropWait.Failed("Сервер Point не настроен")
        return runCatching {
            val c = connect("$base/u/${box.id}/take", "GET")
            val code = c.responseCode
            if (code != 200) {
                c.disconnect()
                return@runCatching if (code == 204) DropWait.Empty else DropWait.Failed(refusal(code))
            }
            val bytes = c.inputStream.readBytes()
            val fileId = c.getHeaderField("X-File-Id")
            val name = dropFileName(decodeName(c.getHeaderField("X-File-Name")))
            val mime = c.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"
            c.disconnect()

            val path = target(name)
            File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)

            // Подтверждение НЕ здесь: пока объект не создан, стирать файл на сервере нельзя —
            // прислал его чужой человек, и повторить он не сможет (#726).
            DropWait.Arrived(DropArrival(path, name, mime, fileId.orEmpty()))
        }.getOrElse { e -> DropWait.Failed(e.message ?: "Нет связи с сервером Point") }
    }

    override suspend fun close(box: DropInboxBox) {
        val base = base() ?: return
        runCatching {
            val c = connect("$base/u/${box.id}/close", "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            c.outputStream.close()
            c.responseCode
            c.disconnect()
        }
    }

    override suspend fun ack(box: DropInboxBox, fileId: String) {
        if (fileId.isBlank()) return
        val base = base() ?: return
        runCatching {
            val c = connect("$base/u/${box.id}/ack", "POST").apply {
                doOutput = true
                setRequestProperty("X-File-Id", fileId)
                setFixedLengthStreamingMode(0)
            }
            c.outputStream.close()
            c.responseCode
            c.disconnect()
        }
    }

    private fun decodeName(header: String?): String = runCatching {
        if (header.isNullOrBlank()) "" else Base64.getDecoder().decode(header.trim()).decodeToString()
    }.getOrDefault("")

    private fun refusal(code: Int): String = when (code) {
        401, 403 -> "Это устройство больше не в вашем круге — войдите заново"
        404 -> "Ссылка больше не работает — выдайте новую"
        in 500..599 -> "Сервер Point не отвечает — попробуйте позже"
        else -> "Сервер Point не принял запрос"
    }

    private fun base(): String? =
        serverUrl()?.trimEnd('/')?.takeIf { it.isNotBlank() && !pass().isNullOrBlank() }

    private fun connect(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method

            // Короткий предел на попытку (#690, #691) — второй рубеж за NetworkAvailability
            // выше; readTimeout — таймаут на отдельное чтение, не на всю передачу, так что
            // живая, но медленная закачка файла не обрывается.
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
}
