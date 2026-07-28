package com.point.data

import com.point.core.flow.ClipPull
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcPairing
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared-clipboard transport over the LAN hop (#161 «общий буфер»). `POST /clipboard` sets the PC's
 * clipboard from the phone's; `GET /clipboard` returns the PC's. The payload's mime + name ride in
 * `X-Clip-Mime` / `X-Clip-Name` (base64, Cyrillic-safe) and the raw bytes are the body — so text,
 * screenshots and files all cross. Same token gate as the rest of the PC link.
 */
class HttpPcClipboardSync @Inject constructor() : PcClipboardSync {

    override suspend fun push(pairing: PcPairing, payload: ClipboardPayload): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = endpoint(pairing)
            c.requestMethod = "POST"
            c.connectTimeout = 3_000
            c.readTimeout = 20_000
            c.setRequestProperty("X-Point-Token", pairing.token)
            c.setRequestProperty("X-Clip-Mime", payload.mime)
            c.setRequestProperty("X-Clip-Name", b64(payload.name))
            c.doOutput = true
            c.setFixedLengthStreamingMode(payload.bytes.size)
            c.outputStream.use { it.write(payload.bytes) }
            val ok = c.responseCode == 200
            c.disconnect()
            ok
        }.getOrDefault(false)
    }

    override suspend fun pull(pairing: PcPairing): ClipPull = withContext(Dispatchers.IO) {
        runCatching {
            val c = endpoint(pairing)
            c.connectTimeout = 3_000
            c.readTimeout = 20_000
            c.setRequestProperty("X-Point-Token", pairing.token)
            if (c.responseCode != 200) {
                c.disconnect()
                return@runCatching ClipPull.Unreachable
            }
            val mime = c.getHeaderField("X-Clip-Mime")?.takeIf { it.isNotBlank() } ?: "text/plain"
            val name = c.getHeaderField("X-Clip-Name")?.let { runCatching { unb64(it) }.getOrDefault("") }.orEmpty()
            val bytes = c.inputStream.readBytes()
            c.disconnect()
            if (bytes.isEmpty()) ClipPull.Empty else ClipPull.Got(ClipboardPayload(mime, name, bytes))
        }.getOrDefault(ClipPull.Unreachable)
    }

    private fun endpoint(pairing: PcPairing) =
        URL("http://${pairing.host}:${pairing.port}/clipboard").openConnection() as HttpURLConnection

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun unb64(s: String): String = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
}
