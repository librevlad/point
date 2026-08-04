package com.point.data

import com.point.core.flow.DropArrival
import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
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
    /** Пропуск устройства в аккаунте (#473): общего пароля приложения больше нет, у каждого свой. */
    private val pass: () -> String?,
    /** Сколько релей держит запрос, пока никто ничего не положил (его потолок — 30 с). */
    private val waitSeconds: Int = 25,
) : DropInbox {

    override suspend fun open(): DropInboxBox? = withContext(Dispatchers.IO) {
        val base = base() ?: return@withContext null
        val id = dropInboxId(ByteArray(20).also { SecureRandom().nextBytes(it) })
        val link = dropInboxLink(base, id) ?: return@withContext null
        runCatching {
            val c = connect("$base/u/open", "POST").apply {
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            c.outputStream.close()
            val ok = c.responseCode in 200..299
            c.disconnect()
            if (ok) DropInboxBox(id, link) else null
        }.getOrNull()
    }

    /**
     * Круг ожидания. Три исхода названы раздельно (#114): релей отвечает `204`, когда за отведённое
     * время никто ничего не положил, — это норма; всё прочее (нет сети, чужой ответ, сломанный
     * кадр) — отказ, и он доезжает до человека словами, а не вечным «Ждём файл…».
     */
    override suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait =
        withContext(Dispatchers.IO) {
            val base = base() ?: return@withContext DropWait.Failed("Сервер Point не настроен")
            runCatching {
                val c = connect("$base/mbx/${box.id}?wait=$waitSeconds", "GET").apply {
                    readTimeout = (waitSeconds + 20) * 1_000
                }
                val code = c.responseCode
                if (code != 200) {
                    c.disconnect()
                    // 204 — «за это ожидание никто ничего не положил», ровно то, ради чего круг и
                    // делается. Любой другой код — сервер отказал, и это другая новость.
                    return@runCatching if (code == 204) {
                        DropWait.Empty
                    } else {
                        DropWait.Failed("Сервер Point ответил $code")
                    }
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
                DropWait.Arrived(DropArrival(path, name, mime))
            }.getOrElse { e -> DropWait.Failed(e.message ?: "Нет связи с сервером Point") }
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
        relayUrl.trimEnd('/').takeIf { it.isNotBlank() && !pass().isNullOrBlank() }

    private fun connect(url: String, method: String): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 20_000
            pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
}
