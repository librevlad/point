package com.point.core.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Разговор с сервером Point по HTTP — **один на телефон и на компьютер** (#472, #473).
 *
 * Раньше такой код жил дважды: свой у `:data` и свой у `:desktop`, и расходились они молча. Здесь
 * он один, потому что в `:core:flow` можно: голая JVM, `HttpURLConnection`, ноль зависимостей.
 *
 * Ручки — те, что построил срез 2 (#471, `relay/point_server/app.py`):
 *
 * ```
 * POST /auth/start           {"kind","name","key_agree","key_sign"}
 *                            → {"login_id","claim_token","user_code","login_url","expires_in","interval"}
 * GET  /auth/session/<id>    Bearer <claim_token>
 *                            → 202 {"status":"pending", …}
 *                            | 200 {"status":"ready","device_id","device_token","kind","name",
 *                                   "account":{"email","name"}}
 *                            | 404 {"error","message"}
 * GET  /circle               Bearer <device_token>
 *                            → {"account":{"email","name"},
 *                               "devices":[{"id","kind","name","last_seen","online","self",…}]}
 * POST /devices/<id>/revoke  Bearer <device_token> → {"revoked","self"}
 * ```
 *
 * `Authorization: Bearer` вместо прежнего `X-Point-App`: общего пароля приложения больше нет, у
 * каждого устройства свой пропуск, и отзывается он поимённо.
 *
 * **Время сервера — в секундах** (`int(time.time())`), у нас — в миллисекундах: перевод делается
 * здесь, на границе, чтобы дальше по коду жила одна единица.
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
            // Открытые ключи устройства поедут здесь же, когда родятся (#474). Пустые поля — не
            // забывчивость: ключей сегодня нет ни у кого, и сервер это знает.
            body = jsonObject("kind" to kind.name, "name" to deviceName),
        )
        if (reply.status != 200) return@io null
        val json = parseJson(reply.body ?: "")
        val loginId = json.str("login_id") ?: return@io null
        val claim = json.str("claim_token") ?: return@io null
        val code = json.str("user_code") ?: return@io null
        val url = json.str("login_url") ?: return@io null
        LoginStart(loginId = loginId, claimToken = claim, code = code, url = url)
    }

    override suspend fun poll(loginId: String, claimToken: String): LoginPoll = io {
        val reply = request(path = "/auth/session/" + encode(loginId), method = "GET", token = claimToken)
        // Не дозвонились — это молчание, а не отказ (#561). Разница не косметическая: отказ
        // терминален, и пока молчание приезжало отказом, один сбой связи навсегда обрывал вход,
        // который сервер уже подтвердил. Человек читал «до сервера не дозвониться» через секунду
        // после того, как тот же сервер ответил на «начать вход».
        if (reply.status == null) return@io LoginPoll.Silent
        // 202 — «человек ещё в браузере». Это не ошибка и не готовность, и отдельный код тут
        // ценнее тела: ждать дальше можно, ничего не разбирая.
        if (reply.status == 202) return@io LoginPoll.Pending
        if (reply.status != 200) return@io refusal(reply)
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
                    val account = (json as? JsonValue.Obj)?.fields?.get("account")
                    LoginPoll.Ready(
                        PointAccount(
                            deviceId = id,
                            deviceToken = token,
                            email = account.str("email").orEmpty(),
                            deviceName = json.str("name").orEmpty(),
                            kind = kindOf(json.str("kind")),
                        ),
                    )
                }
            }
            // Незнакомая форма — не «готово» и не «ждём»: отвечать за сервер мы не будем.
            else -> LoginPoll.Refused(
                what = "Сервер Point ответил непонятно",
                fix = "Попробуйте войти ещё раз.",
            )
        }
    }

    override suspend fun circle(account: PointAccount): CircleAnswer = io {
        val reply = request(path = "/circle", method = "GET", token = account.deviceToken)
        // 401 — отозванное устройство попадает сюда же, и это ровно то, чего мы хотели: «Отключить»
        // действует на следующем же запросе, без списка отозванных.
        if (reply.status == 401 || reply.status == 403) return@io CircleAnswer.Revoked
        if (reply.status != 200) return@io CircleAnswer.Unreachable
        val json = parseJson(reply.body ?: "") ?: return@io CircleAnswer.Unreachable
        CircleAnswer.Circle(
            json.array("devices").mapNotNull { item ->
                val id = item.str("id") ?: return@mapNotNull null
                CircleDevice(
                    id = id,
                    kind = kindOf(item.str("kind")),
                    name = item.str("name")?.takeIf { it.isNotBlank() } ?: "Устройство",
                    lastSeenMillis = item.long("last_seen")?.takeIf { it > 0 }?.times(1_000L),
                    self = item.bool("self") ?: (id == account.deviceId),
                )
            },
        )
    }

    override suspend fun revoke(account: PointAccount, deviceId: String): Boolean = io {
        request(
            path = "/devices/" + encode(deviceId) + "/revoke",
            method = "POST",
            token = account.deviceToken,
            body = "",
        ).status == 200
    }

    // --- HTTP, и ничего больше ---

    private class Reply(val status: Int?, val body: String?)

    /**
     * Отказ словами сервера, если он их сказал, и своими — если нет.
     *
     * Сервер отвечает `{"error","message"}`, и его `message` написан для человека («Вход не найден
     * или уже завершён. Начните заново.»). Заменять его своим общим текстом значило бы выбросить
     * то, что нам сказали прямо; совет («что делать») остаётся наш — сервер его не даёт.
     */
    private fun refusal(reply: Reply): LoginPoll.Refused {
        val ours = accountRefusal(reply.status)
        val said = parseJson(reply.body ?: "").str("message")?.takeIf { it.isNotBlank() }
        return LoginPoll.Refused(what = said ?: ours.what, fix = ours.fix)
    }

    private fun request(path: String, method: String, token: String? = null, body: String? = null): Reply =
        runCatching {
            val c = URL(base + path).openConnection() as HttpURLConnection
            // Пиннинга нет: у сервера настоящий сертификат Let'''s Encrypt на point.leerio.app
            // (#470 выкачен 04.08.2026), и доверяем мы обычной цепочке. Самоподписанный жил ровно
            // до этого дня — держать его дальше значило бы возить с собой мёртвую защиту.
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
    }
}

/** Вид устройства из строки сервера; незнакомое — телефон (их большинство, и это не ложь о правах). */
internal fun kindOf(raw: String?): DeviceKind =
    if (raw.equals("PC", ignoreCase = true)) DeviceKind.PC else DeviceKind.PHONE
