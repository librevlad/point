package com.point.data

import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcPairing
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared-clipboard transport over the LAN hop (#161 «общий буфер»). `POST /clipboard` sets the PC's
 * clipboard from the phone's; `GET /clipboard` returns the PC's. Plain text, UTF-8 — Cyrillic safe.
 * Same token gate as the rest of the PC link.
 */
class HttpPcClipboardSync @Inject constructor() : PcClipboardSync {

    override suspend fun push(pairing: PcPairing, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://${pairing.host}:${pairing.port}/clipboard").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 3_000
            c.readTimeout = 5_000
            c.setRequestProperty("X-Point-Token", pairing.token)
            c.doOutput = true
            c.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val ok = c.responseCode == 200
            c.disconnect()
            ok
        }.getOrDefault(false)
    }

    override suspend fun pull(pairing: PcPairing): String? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("http://${pairing.host}:${pairing.port}/clipboard").openConnection() as HttpURLConnection
            c.connectTimeout = 3_000
            c.readTimeout = 5_000
            c.setRequestProperty("X-Point-Token", pairing.token)
            val text = if (c.responseCode == 200) c.inputStream.bufferedReader(Charsets.UTF_8).readText() else null
            c.disconnect()
            text
        }.getOrNull()
    }
}
