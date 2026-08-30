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

    /**
     * [serverNowMs] — часы сервера в тот миг, когда ящик отвечал (#1321). По ним и только по
     * ним считается, сколько письмо пролежало: время в имени письма поставил тот же сервер.
     * `null` — ответ о времени не сказал, и возраст письма отсюда не узнать.
     */
    class Letter(
        val code: Int,
        val blob: ByteArray?,
        val id: String = "",
        val serverNowMs: Long? = null,
    )

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
     *
     * Вместе с письмом ответ приносит часы сервера (#1321): по ним получатель судит, сколько
     * письмо пролежало, — своих часов для этого не хватает, они с серверными не сверены.
     */
    fun take(deviceId: String, keep: (Letter) -> Unit): Letter {
        val letter = runCatching {
            val c = open("$base/mbx/$deviceId")
            val code = c.responseCode
            val blob = if (code == 200) c.inputStream.readBytes() else null
            val id = c.getHeaderField("X-Blob-Id").orEmpty()
            val now = c.getHeaderFieldDate(SERVER_TIME, 0L).takeIf { it > 0 }
            c.disconnect()
            Letter(code, blob, id, now)
        }.getOrElse { Letter(NETWORK, null) }

        if (letter.blob == null || letter.id.isBlank()) return letter
        keep(letter)
        confirm(deviceId, letter.id)
        return letter
    }

    /**
     * Разобрать то, что осталось в ящике с прошлых разговоров.
     *
     * Раньше здесь всё выбрасывалось: считалось, что в ящике лежат только ответы на вопросы,
     * которых уже никто не ждёт. С односторонней связкой это было правдой. Как только
     * компьютер научился просить (#817), тот же код стал убивать просьбы: письмо доходило и
     * гибло при первой же исходящей отправке телефона.
     *
     * Теперь письмо сначала показывают [keep], и только если оно не понадобилось — забывают.
     * Ответы на протухшие свои вопросы по-прежнему уходят: иначе ящик станет свалкой. Всё
     * непонятое доживёт своё на сервере — там письма живут сутки.
     */
    fun drain(deviceId: String) {
        repeat(MAX_DRAIN) {
            if (take(deviceId) { }.blob == null) return
        }
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

        /** Чем ответ называет своё время: заголовок даты есть у любого ответа сервера. */
        private const val SERVER_TIME = "Date"
    }
}

/**
 * Сколько письмо пролежало в ящике (#1321).
 *
 * Обе величины — по часам сервера. Время, с которого начинается имя письма, поставил он же
 * (по нему ящик и отдаёт самое старое первым), а `serverNowMs` — время того же ответа ящика.
 * Часы получателя в счёт не входят вовсе, и это главное: они с серверными не сверены — уходят
 * вперёд после сна, на машине без синхронизации, в виртуалке. Стоило бы вычесть чужое время
 * из своего, и живая просьба человека, стоящего перед экраном, выглядела бы пролежавшей
 * сутки: вместо слов исхода он получал бы обещание работы, а родившийся файл уезжал бы в
 * список «с компьютера» вместо прямого ответа.
 *
 * `null` — «не знаю»: имя без времени или ответ без даты. Возраст, которого не знаешь,
 * выдумывать нельзя, и нуль здесь такая же выдумка, как любое другое число: незнание едет
 * дальше незнанием, а как с ним поступить — решает тот, кто отвечает.
 */
fun letterAgeMs(letterId: String, serverNowMs: Long?): Long? {
    val now = serverNowMs ?: return null
    val posted = letterPostedAtMs(letterId) ?: return null
    return (now - posted).coerceAtLeast(0)
}

/** Время в имени письма — то, что поставил сервер, кладя его в ящик. */
private fun letterPostedAtMs(letterId: String): Long? {
    val stamp = letterId.substringBefore('-')
    if (stamp.isEmpty() || !stamp.all(Char::isDigit)) return null
    return stamp.toLongOrNull()?.div(1_000_000)
}
