package com.point.data

import android.util.Base64
import com.point.core.flow.DropLink
import com.point.core.flow.RelayTls
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Дать ссылку» поверх релея (#388).
 *
 * Файл кладётся под неугадываемым адресом (160 бит) и живёт сутки — столько же, сколько всё
 * остальное в релее. Забирают его обычным браузером, поэтому секрет приложения при скачивании не
 * нужен: ссылка и есть пропуск.
 */
class RelayDropLink(
    private val relayUrl: String,
    /** Пропуск устройства в аккаунте (#473): общего пароля приложения больше нет, у каждого свой. */
    private val pass: () -> String?,
) : DropLink {

    override suspend fun give(path: String, fileName: String, mime: String): String? =
        withContext(Dispatchers.IO) {
            val base = relayUrl.trimEnd('/').takeIf { it.isNotBlank() && !pass().isNullOrBlank() }
                ?: return@withContext null
            val file = File(path).takeIf { it.isFile } ?: return@withContext null
            if (file.length() > MAX_DROP_BYTES) return@withContext null

            runCatching {
                val c = (URL("$base/d").openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = RelayTls.socketFactory
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 60_000
                    pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
                    // Имя едет в base64: в HTTP-заголовке кириллица превращается в мусор, а
                    // «отчёт.pdf» обязан остаться отчётом и у получателя.
                    setRequestProperty("X-Drop-Name", Base64.encodeToString(fileName.toByteArray(), Base64.NO_WRAP))
                    setRequestProperty("X-Drop-Mime", mime)
                    setFixedLengthStreamingMode(file.length())
                }
                file.inputStream().use { input -> c.outputStream.use { out -> input.copyTo(out) } }
                val id = if (c.responseCode in 200..299) c.inputStream.readBytes().decodeToString().trim() else null
                c.disconnect()
                id?.takeIf { it.isNotBlank() }?.let { "$base/d/$it" }
            }.getOrNull()
        }

    private companion object {
        /** Столько же, сколько принимает релей (MAX_BLOB): больше он всё равно отрежет. */
        const val MAX_DROP_BYTES = 50L * 1024 * 1024
    }
}
