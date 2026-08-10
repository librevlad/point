package com.point.core.flow

import java.net.URL
import java.net.HttpURLConnection

/**
 * Почта между своими устройствами.
 *
 * Сервер отдаёт письмо, но держит его у себя, пока приём не подтверждён. Значит
 * подтверждение — это слово «сохранено», а не «скачано»: пока письмо не легло на
 * диск, подтверждать нечего. Раньше подтверждение уходило сразу после скачивания, и
 * падение на разборе стирало объект человека с сервера навсегда (#680).
 */
class Mailbox(
    private val base: String,

    private val pass: () -> String?,
    private val connectTimeoutMs: Int = 5_000,

    // Короткий предел на попытку (#690): письма здесь маленькие, и если релей
    // молчит на живой сети, знать об этом за 30 секунд незачем — быстрее уступить
    // место следующей попытке, чем держать человека перед крутящимся кругом.
    private val readTimeoutMs: Int = 15_000,
) {

    class Letter(val code: Int, val blob: ByteArray?, val id: String = "")

    fun post(deviceId: String, blob: ByteArray): Int = runCatching {
        val c = open("$base/mbx/$deviceId")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(blob.size)
        c.outputStream.use { it.write(blob) }
        val code = c.responseCode
        c.disconnect()
        code
    }.getOrElse { NETWORK }

    /**
     * Забрать одно письмо. Приём подтверждается только после того, как [keep]
     * вернулся: сначала на диск, потом подтверждение.
     *
     * Сбой сохранения выходит наружу и оставляет письмо на сервере — оно приедет
     * снова. Разбор к этому моменту ещё не начинался: он отдельный шаг, и упасть
     * на нём уже не страшно.
     */
    fun take(deviceId: String, keep: (Letter) -> Unit): Letter {
        val letter = runCatching {
            val c = open("$base/mbx/$deviceId")
            val code = c.responseCode
            val blob = if (code == 200) c.inputStream.readBytes() else null
            val id = c.getHeaderField("X-Blob-Id").orEmpty()
            c.disconnect()
            Letter(code, blob, id)
        }.getOrElse { Letter(NETWORK, null) }

        if (letter.blob == null || letter.id.isBlank()) return letter
        keep(letter)
        confirm(deviceId, letter.id)
        return letter
    }

    /**
     * Выбросить то, что осталось в ящике с прошлых разговоров. Хранить нечего:
     * здесь лежат ответы на вопросы, которые уже никто не ждёт.
     */
    fun drain(deviceId: String) {
        repeat(MAX_DRAIN) { if (take(deviceId) { }.blob == null) return }
    }

    private fun confirm(deviceId: String, letterId: String) {
        runCatching {
            val c = open("$base/mbx/$deviceId/ack?blob=$letterId")
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
            c.responseCode
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    companion object {

        const val NETWORK = -1

        private const val MAX_DRAIN = 8
    }
}
