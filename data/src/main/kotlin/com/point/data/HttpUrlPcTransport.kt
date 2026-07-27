package com.point.data

import com.point.core.flow.PcPairing
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.decodePcCaps
import com.point.core.flow.PcTransport
import com.point.core.flow.encodePcMeta
import com.point.core.model.PointObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phone→PC over plain HttpURLConnection (LAN, #147). Headers are base64 so Cyrillic
 * survives ASCII header rules; the body streams with a fixed length — a big photo
 * never sits in memory.
 */
class HttpUrlPcTransport @Inject constructor() : PcTransport {

    override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("http://$host:$port/pair").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 3_000
                c.readTimeout = PAIR_READ_TIMEOUT_MS // the user is answering on the PC
                c.setRequestProperty("X-Point-Name", b64(deviceName))
                c.doOutput = true
                c.outputStream.use { it.write(ByteArray(0)) }
                val token = if (c.responseCode == 200) {
                    c.inputStream.bufferedReader().readText().trim()
                } else {
                    null
                }
                c.disconnect()
                token?.takeIf { it.isNotBlank() }?.let { PcPairing(host, port, it) }
            }.getOrNull()
        }

    override suspend fun send(
        pairing: PcPairing,
        obj: PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String?,
    ): PcSendOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(obj.uri.value)
            val c = URL("http://${pairing.host}:${pairing.port}/receive").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 3_000
            c.readTimeout = 15_000
            c.setRequestProperty("X-Point-Token", pairing.token)
            c.setRequestProperty("X-Point-Name", b64(fileName))
            c.setRequestProperty("X-Point-Mime", obj.mime)
            c.setRequestProperty("X-Point-Meta", b64(encodePcMeta(meta)))
            action?.let { c.setRequestProperty("X-Point-Action", b64(it)) }
            c.doOutput = true
            c.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { input -> c.outputStream.use { input.copyTo(it) } }
            val code = c.responseCode
            c.disconnect()
            when (code) {
                200 -> PcSendOutcome.Sent
                401, 403 -> PcSendOutcome.Rejected
                else -> PcSendOutcome.Unreachable("HTTP $code")
            }
        }.getOrElse { PcSendOutcome.Unreachable(it.message ?: "нет связи") }
    }

    override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("http://${pairing.host}:${pairing.port}/caps").openConnection() as HttpURLConnection
                c.connectTimeout = 3_000
                c.readTimeout = 5_000
                c.setRequestProperty("X-Point-Token", pairing.token)
                val caps = if (c.responseCode == 200) {
                    decodePcCaps(c.inputStream.bufferedReader().readText())
                } else {
                    null
                }
                c.disconnect()
                caps
            }.getOrNull()
        }

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    private companion object {
        const val PAIR_READ_TIMEOUT_MS = 65_000
    }
}
