package com.point.core.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HttpAccountClient(

    serverUrl: String? = null,

    private val publicKey: String = "",

    private val handoff: Boolean = false,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 15_000,
) : AccountClient {

    private val base = PointServer.base(serverUrl)

    override suspend fun start(deviceName: String, kind: DeviceKind): LoginStart? = io {
        val reply = request(
            path = "/auth/start",
            method = "POST",
            body = jsonObject(
                fields = listOf("kind" to kind.name, "name" to deviceName, "key_agree" to publicKey),
                flags = listOf("handoff" to handoff),
            ),
        )
        if (reply.status != 200) return@io null
        val json = parseJson(reply.body ?: "")
        val loginId = json.str("login_id") ?: return@io null
        val claim = json.str("claim_token") ?: return@io null
        val code = json.str("user_code") ?: return@io null
        val url = json.str("login_url") ?: return@io null

        val oneStep = json.bool("handoff") ?: false
        LoginStart(loginId = loginId, claimToken = claim, code = code, url = url, handoff = oneStep)
    }

    override suspend fun poll(loginId: String, claimToken: String): LoginPoll = io {
        val reply = request(path = "/auth/session/" + encode(loginId), method = "GET", token = claimToken)

        if (reply.status == null) return@io LoginPoll.Silent

        if (reply.status == 202) return@io LoginPoll.Pending
        if (reply.status != 200) return@io refusal(reply)
        val json = parseJson(reply.body ?: "")
        when (json.str("status")) {
            "pending" -> LoginPoll.Pending
            "ready" -> {
                val id = json.str("device_id")
                val token = json.str("device_token")
                if (id.isNullOrBlank() || token.isNullOrBlank()) {

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

            else -> LoginPoll.Refused(
                what = "Сервер Point ответил непонятно",
                fix = "Попробуйте войти ещё раз.",
            )
        }
    }

    override suspend fun circle(account: PointAccount): CircleAnswer = io {
        val reply = request(path = "/circle", method = "GET", token = account.deviceToken)

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
                    key = item.str("key_agree").orEmpty(),
                )
            },
        )
    }

    override suspend fun enroll(account: PointAccount, publicKey: String): Boolean = io {
        request(
            path = "/enroll",
            method = "POST",
            token = account.deviceToken,
            body = jsonObject("key_agree" to publicKey),
        ).status == 200
    }

    override suspend fun revoke(account: PointAccount, deviceId: String): Boolean = io {
        request(
            path = "/devices/" + encode(deviceId) + "/revoke",
            method = "POST",
            token = account.deviceToken,
            body = "",
        ).status == 200
    }

    override suspend fun deleteAccount(account: PointAccount): Boolean = io {
        request(path = "/account", method = "DELETE", token = account.deviceToken).status == 200
    }

    private class Reply(val status: Int?, val body: String?)

    private fun refusal(reply: Reply): LoginPoll.Refused {
        val ours = accountRefusal(reply.status)
        val said = parseJson(reply.body ?: "").str("message")?.takeIf { it.isNotBlank() }
        return LoginPoll.Refused(what = said ?: ours.what, fix = ours.fix)
    }

    private fun request(path: String, method: String, token: String? = null, body: String? = null): Reply =
        runCatching {
            val c = URL(base + path).openConnection() as HttpURLConnection

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

internal fun kindOf(raw: String?): DeviceKind =
    if (raw.equals("PC", ignoreCase = true)) DeviceKind.PC else DeviceKind.PHONE
