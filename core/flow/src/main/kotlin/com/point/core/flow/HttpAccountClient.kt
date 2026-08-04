package com.point.core.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Разговор с сервером Point по HTTP — **один на телефон и на компьютер** (#472, #473).
 *
 * Раньше такой код жил дважды: свой у `:data` и свой у `:desktop`, и расходились они молча. Здесь
 * он один, потому что в `:core:flow` можно: голая JVM, `HttpURLConnection`, ноль зависимостей.
 *
 * Ручки, которых ждёт клиент (контракт с сервером — срез 2, #471):
 *
 * ```
 * POST /auth/start            {"name","kind"}            → {"login_id","code","url"}
 * GET  /auth/session/<id>                                → {"status":"pending"}
 *                                                        | {"status":"ready","device_id","device_token","email"}
 *                                                        | {"status":"refused","what","fix"}
 * GET  /circle                Bearer <device_token>      → {"devices":[{"id","kind","name","last_seen","self"}]}
 * POST /devices/<id>/revoke   Bearer <device_token>      → 200
 * POST /signout               Bearer <device_token>      → 200
 * ```
 *
 * `Authorization: Bearer` вместо прежнего `X-Point-App`: общего пароля приложения больше нет, у
 * каждого устройства свой пропуск, и отзывается он поимённо.
 */
class HttpAccountClient(
    /** База сервера; пусто — берётся [PointServer.DEFAULT_URL]. */
    serverUrl: String? = null,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,
) : AccountClient {

    private val base = PointServer.base(serverUrl)

    override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? = io {
        val reply = request(
            path = "/auth/start",
            method = "POST",
            body = jsonObject("name" to deviceName, "kind" to kind.name),
        )
        if (reply.status != 200) return@io null
        val json = parseJson(reply.body ?: "")
        val loginId = json.str("login_id") ?: return@io null
        val code = json.str("code") ?: return@io null
        val url = json.str("url") ?: return@io null
        LoginStart(loginId, code, url)
    }

    override suspend fun poll(loginId: String): LoginPoll = io {
        val reply = request(path = "/auth/session/" + encode(loginId), method = "GET")
        if (reply.status != 200) return@io accountRefusal(reply.status).toPoll()
        val json = parseJson(reply.body ?: "")
        when (json.str("status")) {
            "pending" -> LoginPoll.Pending
            "ready" -> {
                val id = json.str("device_id")
                val token = json.str("device_token")
                if (id.isNullOrBlank() || token.isNullOrBlank()) {
                    // Сервер сказал «готово» и не дал пропуска — это не «ещё подождём», это поломка,
                    // и молчать о ней нельзя: человек остался бы у крутящегося экрана навсегда.
                    LoginPoll.Refused(
                        what = "Сервер Point ответил без пропуска устройства",
                        fix = "Попробуйте войти ещё раз.",
                    )
                } else {
                    LoginPoll.Ready(
                        PointAccount(
                            deviceId = id,
                            deviceToken = token,
                            email = json.str("email").orEmpty(),
                            deviceName = json.str("name").orEmpty(),
                            kind = kindOf(json.str("kind")),
                        ),
                    )
                }
            }
            "refused" -> LoginPoll.Refused(
                what = json.str("what") ?: "Вход не состоялся",
                fix = json.str("fix") ?: "Попробуйте войти ещё раз.",
            )
            // Незнакомая форма — не «готово» и не «ждём»: отвечать за сервер мы не будем.
            else -> LoginPoll.Refused(
                what = "Сервер Point ответил непонятно",
                fix = "Попробуйте войти ещё раз.",
            )
        }
    }

    override suspend fun circle(account: PointAccount): List<CircleDevice>? = io {
        val reply = request(path = "/circle", method = "GET", token = account.deviceToken)
        if (reply.status != 200) return@io null
        val json = parseJson(reply.body ?: "") ?: return@io null
        json.array("devices").mapNotNull { item ->
            val id = item.str("id") ?: return@mapNotNull null
            CircleDevice(
                id = id,
                kind = kindOf(item.str("kind")),
                name = item.str("name")?.takeIf { it.isNotBlank() } ?: "Устройство",
                lastSeenMillis = item.long("last_seen"),
                self = item.bool("self") ?: (id == account.deviceId),
            )
        }
    }

    override suspend fun revoke(account: PointAccount, deviceId: String): Boolean = io {
        request(
            path = "/devices/" + encode(deviceId) + "/revoke",
            method = "POST",
            token = account.deviceToken,
            body = "",
        ).status == 200
    }

    override suspend fun signOut(account: PointAccount): Boolean = io {
        request(path = "/signout", method = "POST", token = account.deviceToken, body = "").status == 200
    }

    // --- HTTP, и ничего больше ---

    private class Reply(val status: Int?, val body: String?)

    private fun request(path: String, method: String, token: String? = null, body: String? = null): Reply =
        runCatching {
            val c = URL(base + path).openConnection() as HttpURLConnection
            // Пиннинг остаётся ровно там, где сервер ещё стоит на самоподписанном сертификате
            // (сегодняшний адрес по IP). У домена с настоящим сертификатом пинить нечего — и не
            // нужно: срез 1 (#470) уносит пиннинг совсем.
            if (c is HttpsURLConnection && base.contains(PINNED_HOST)) c.sslSocketFactory = RelayTls.socketFactory
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Accept", "application/json")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val text = runCatching {
                (if (status in 200..299) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8)
            }.getOrNull()
            c.disconnect()
            Reply(status, text)
        }.getOrElse { Reply(null, null) }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private companion object {
        /** Адрес сегодняшнего сервера с самоподписанным сертификатом (см. [RelayTls]). */
        const val PINNED_HOST = "35.185.31.106"
    }
}

/** «Сервер отказал» одинаково выглядит и на старте, и на опросе — форма ответа разная. */
private fun SignIn.Refused.toPoll(): LoginPoll = LoginPoll.Refused(what, fix)

/** Вид устройства из строки сервера; незнакомое — телефон (их большинство, и это не ложь о правах). */
internal fun kindOf(raw: String?): DeviceKind =
    if (raw.equals("PC", ignoreCase = true)) DeviceKind.PC else DeviceKind.PHONE
