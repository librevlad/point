package com.point.data

import com.point.core.flow.DropArrival
import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
import com.point.core.flow.NO_NETWORK_TEXT
import com.point.core.flow.NetworkAvailability
import com.point.core.flow.dropFileName
import com.point.core.flow.dropInboxLink
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RelayDropInbox(
    private val relayUrl: String,

    private val pass: () -> String?,

    private val network: NetworkAvailability,
) : DropInbox {

    override suspend fun open(): DropInboxBox? = withContext(Dispatchers.IO) {
        // Перед выходом наружу — спросить телефон, есть ли сеть вообще (#690, #691).
        if (!network.isAvailable()) return@withContext null
        val base = base() ?: return@withContext null
        runCatching {
            val c = connect("$base/u/open", "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            c.outputStream.close()
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return@runCatching null
            }
            val body = c.inputStream.readBytes().decodeToString()
            c.disconnect()

            val answer = org.json.JSONObject(body)
            val box = answer.optString("box").takeIf { it.isNotBlank() } ?: return@runCatching null
            val link = answer.optString("url").takeIf { it.isNotBlank() }
                ?: dropInboxLink(base, box) ?: return@runCatching null
            DropInboxBox(box, link)
        }.getOrNull()
    }

    override suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait =
        withContext(Dispatchers.IO) {
            if (!network.isAvailable()) return@withContext DropWait.Failed(NO_NETWORK_TEXT)
            val base = base() ?: return@withContext DropWait.Failed("Сервер Point не настроен")
            runCatching {
                val c = connect("$base/u/${box.id}/take", "GET")
                val code = c.responseCode
                if (code != 200) {
                    c.disconnect()
                    return@runCatching if (code == 204) {
                        DropWait.Empty
                    } else {
                        DropWait.Failed(refusal(code))
                    }
                }
                val bytes = c.inputStream.readBytes()
                val fileId = c.getHeaderField("X-File-Id")

                val name = dropFileName(decodeName(c.getHeaderField("X-File-Name")))
                val mime = c.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                c.disconnect()

                val path = target(name)
                File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)

                ack(base, box.id, fileId)
                DropWait.Arrived(DropArrival(path, name, mime))
            }.getOrElse { e -> DropWait.Failed(e.message ?: "Нет связи с сервером Point") }
        }

    private fun ack(base: String, box: String, fileId: String?) {
        if (fileId.isNullOrBlank()) return
        runCatching {
            val c = connect("$base/u/$box/ack", "POST").apply {
                doOutput = true
                setRequestProperty("X-File-Id", fileId)
                setFixedLengthStreamingMode(0)
            }
            c.outputStream.close()
            c.responseCode
            c.disconnect()
        }
    }

    private fun decodeName(header: String?): String = runCatching {
        if (header.isNullOrBlank()) "" else android.util.Base64.decode(header, android.util.Base64.DEFAULT)
            .decodeToString()
    }.getOrDefault("")

    private fun refusal(code: Int): String = when (code) {
        401, 403 -> "Это устройство больше не в вашем круге — войдите заново"
        404 -> "Ссылка больше не работает — выдайте новую"
        in 500..599 -> "Сервер Point не отвечает — попробуйте позже"
        else -> "Сервер Point не принял запрос"
    }

    private fun base(): String? =
        relayUrl.trimEnd('/').takeIf { it.isNotBlank() && !pass().isNullOrBlank() }

    private fun connect(url: String, method: String): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = method

            // Короткий предел на попытку (#690, #691) — второй рубеж за
            // NetworkAvailability выше; readTimeout — таймаут на отдельное чтение, не
            // на всю передачу, так что живая, но медленная закачка файла не обрывается.
            connectTimeout = 8_000
            readTimeout = 15_000
            pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
}
