package com.point.core.flow

import java.net.URL
import java.net.HttpURLConnection

/**
 * Ящик устройства на сервере Point (#475) — единственная дорога между телефоном и компьютером.
 *
 * Три движения и всё: положить письмо в ящик СВОЕГО устройства (`POST /mbx/{device_id}`), забрать
 * из своего (`GET`), подтвердить (`POST …/ack?blob=`). Изоляция сделана формой запроса: хозяин
 * берётся из пропуска устройства, и чужому в ящик не написать — проверять права отдельно негде и
 * забыть их негде.
 *
 * **Долгого ожидания у сервера нет**: `GET` отвечает сразу — письмо или 204. Поэтому ожидание
 * ответа здесь короткими опросами с паузой, а не одним висящим запросом. Цена названа: секунда
 * задержки на ответ и один запрос в секунду, пока кто-то ждёт.
 *
 * Забранное подтверждается **сразу**, даже если разобрать его не вышло: сервер отдаёт старейшее
 * письмо, и нерасшифрованное иначе навсегда встанет головой очереди и заморозит канал (урок #271).
 */
class Mailbox(
    private val base: String,
    /** Пропуск устройства в аккаунте: у каждого свой, отзывается поимённо. */
    private val pass: () -> String?,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
) {

    /** Что ответил ящик: [code] (или [NETWORK] — не дозвонились) и письмо, если оно было. */
    class Letter(val code: Int, val blob: ByteArray?)

    /** Положить письмо в ящик устройства [deviceId]. Возвращает код ответа. */
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

    /** Забрать одно письмо из своего ящика и сразу подтвердить его. */
    fun take(deviceId: String): Letter = runCatching {
        val c = open("$base/mbx/$deviceId")
        val code = c.responseCode
        val blob = if (code == 200) c.inputStream.readBytes() else null
        val blobId = c.getHeaderField("X-Blob-Id")
        c.disconnect()
        if (blob != null && blobId != null) ack(deviceId, blobId)
        Letter(code, blob)
    }.getOrElse { Letter(NETWORK, null) }

    /** Опустошить свой ящик от старого — чтобы ждать только свой ответ, а не чужие остатки. */
    fun drain(deviceId: String) {
        repeat(MAX_DRAIN) { if (take(deviceId).blob == null) return }
    }

    private fun ack(deviceId: String, blobId: String) {
        runCatching {
            val c = open("$base/mbx/$deviceId/ack?blob=$blobId")
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
        /** «Не дозвонились» — не код ответа, поэтому и не может совпасть ни с одним. */
        const val NETWORK = -1

        /** Дренаж ограничен: против сломанного сервера не крутимся вечно. */
        private const val MAX_DRAIN = 8
    }
}
