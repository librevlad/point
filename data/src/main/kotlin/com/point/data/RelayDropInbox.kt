package com.point.data

import com.point.core.flow.DropArrival
import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import com.point.core.flow.RelayTls
import com.point.core.flow.decodePcFrame
import com.point.core.flow.dropFileName
import com.point.core.flow.dropInboxId
import com.point.core.flow.dropInboxLink
import java.io.File
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Принять файл» поверх релея (#388).
 *
 * Ящик заводится телефоном (`POST /u/<box>/open` с секретом приложения) и живёт сутки. Чужой
 * человек кладёт файл в него со страницы приёма — без секрета, по одному лишь адресу; телефон
 * забирает файл тем же путём, каким забирает всё остальное (`GET /mbx/<box>` + `ack`).
 *
 * Кадр приезжает **не зашифрованным**: ключа у чужого браузера нет и быть не может. Формат тот же,
 * что у пары устройств ([decodePcFrame]) — своего для приёма не заводим.
 */
class RelayDropInbox(
    private val relayUrl: String,
    private val appSecret: String,
    /** Сколько релей держит запрос, пока никто ничего не положил (его потолок — 30 с). */
    private val waitSeconds: Int = 25,
) : DropInbox {

    override suspend fun open(): DropInboxBox? = withContext(Dispatchers.IO) {
        val base = base() ?: return@withContext null
        val id = dropInboxId(ByteArray(20).also { SecureRandom().nextBytes(it) })
        val link = dropInboxLink(base, id) ?: return@withContext null
        runCatching {
            val c = connect("$base/u/$id/open", "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            c.outputStream.close()
            val ok = c.responseCode in 200..299
            c.disconnect()
            if (ok) DropInboxBox(id, link) else null
        }.getOrNull()
    }

    override suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropArrival? =
        withContext(Dispatchers.IO) {
            val base = base() ?: return@withContext null
            runCatching {
                val c = connect("$base/mbx/${box.id}?wait=$waitSeconds", "GET").apply {
                    readTimeout = (waitSeconds + 20) * 1_000
                }
                if (c.responseCode != 200) {
                    c.disconnect()
                    return@runCatching null
                }
                val blob = c.inputStream.readBytes()
                val blobId = c.getHeaderField("X-Blob-Id")
                c.disconnect()

                val frame = decodePcFrame(blob)
                // Имя дал чужой человек — на диск оно идёт только через чистую проверку.
                val name = dropFileName(frame.meta["name"].orEmpty())
                val mime = frame.meta["mime"]?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val path = target(name)
                File(path).apply { parentFile?.mkdirs() }.writeBytes(frame.bytes)
                // Забрали — освобождаем ящик: иначе тот же файл приехал бы ещё раз на следующем
                // круге ожидания, и человек получил бы его дважды.
                ack(base, box.id, blobId)
                DropArrival(path, name, mime)
            }.getOrNull()
        }

    private fun ack(base: String, box: String, blobId: String?) {
        if (blobId.isNullOrBlank()) return
        runCatching {
            val c = connect("$base/mbx/$box/ack", "POST").apply {
                doOutput = true
                setRequestProperty("X-Blob-Id", blobId)
                setFixedLengthStreamingMode(0)
            }
            c.outputStream.close()
            c.responseCode
            c.disconnect()
        }
    }

    private fun base(): String? =
        relayUrl.trimEnd('/').takeIf { it.isNotBlank() && appSecret.isNotBlank() }

    private fun connect(url: String, method: String): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = RelayTls.socketFactory
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("X-Point-App", appSecret)
        }
}
