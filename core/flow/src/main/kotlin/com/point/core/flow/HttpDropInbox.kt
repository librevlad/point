package com.point.core.flow

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *
 * Сам разговор идёт на [Dispatchers.IO] внутри — звать можно откуда угодно, в том числе с
 * главного потока (#1077). При переезде сюда из `:data` (#749) это потерялось: Android на
 * главном потоке сеть запрещает, вызов падал ещё до выхода наружу, а падение звалось «Сервер
 * Point не ответил» — при сервере, который в ту же минуту отвечал другим устройствам.
 */
class HttpDropInbox(
    private val serverUrl: () -> String?,
    private val pass: () -> String?,
    private val network: NetworkAvailability = NetworkAvailability { true },
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,

    /**
     * След сорвавшегося вызова — в журнал устройства, а не человеку на экран (#1077).
     *
     * Человеку — слово из словаря (#797), нам — чем именно сорвалось: класс и сообщение сбоя.
     * Раньше `ack` и `close` роняли своё падение вообще молча, и закрытая ли дверь — узнать
     * было нечем.
     */
    private val log: (what: String, error: Throwable) -> Unit = { _, _ -> },
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
        return io {
            runCatching {
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

            // Сорвался сам вызов — и у этой беды имя по её природе (#1077): разговор не состоялся
            // (сеть) — про сервер; сломалось на устройстве — про устройство. Чем именно сломалось,
            // человеку не говорят — это уходит в журнал.
            }.getOrElse { e -> log(OPEN_STEP, e); DropOpen.Refused(dropCallBroke(e)) }
        }
    }

    override suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait {
        if (!network.isAvailable()) return DropWait.Failed(NO_NETWORK_TEXT)
        val base = base() ?: return DropWait.Failed("Сервер Point не настроен")
        return io {
            runCatching {
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

            // Слово из словаря, а не `e.message` (#1077): сообщение сбоя — чужой английский
            // («Failed to connect to /10.0.2.2»), и на компьютере оно уходило человеку на экран.
            }.getOrElse { e -> log(AWAIT_STEP, e); DropWait.Failed(dropCallBroke(e)) }
        }
    }

    override suspend fun close(box: DropInboxBox) {
        val base = base() ?: return
        io {
            runCatching {
                val c = connect("$base/u/${box.id}/close", "POST").apply {
                    doOutput = true
                    setFixedLengthStreamingMode(0)
                }
                c.outputStream.close()
                c.responseCode
                c.disconnect()
            }.onFailure { e -> log(CLOSE_STEP, e) }
        }
    }

    override suspend fun ack(box: DropInboxBox, fileId: String) {
        if (fileId.isBlank()) return
        val base = base() ?: return
        io {
            runCatching {
                val c = connect("$base/u/${box.id}/ack", "POST").apply {
                    doOutput = true
                    setRequestProperty("X-File-Id", fileId)
                    setFixedLengthStreamingMode(0)
                }
                c.outputStream.close()
                c.responseCode
                c.disconnect()
            }.onFailure { e -> log(ACK_STEP, e) }
        }
    }

    /** Блокирующий HTTP — только на потоке ввода-вывода, с какого бы потока ни позвали (#1077). */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

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

    /** Какой шаг приёма сорвался — это в журнал, человеку от названия шага пользы нет (#1077). */
    private companion object {
        const val OPEN_STEP = "приём файла — открыть ящик"
        const val AWAIT_STEP = "приём файла — дождаться файла"
        const val ACK_STEP = "приём файла — подтвердить приём"
        const val CLOSE_STEP = "приём файла — закрыть ящик"
    }
}
