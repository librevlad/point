package com.point.data

import com.point.core.flow.DropArrival
import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
import com.point.core.flow.dropFileName
import com.point.core.flow.dropInboxLink
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Принять файл» поверх сервера аккаунтов (#388, #476).
 *
 * Ящик заводит телефон (`POST /u/open` под своим пропуском), и **адрес ящика называет сервер** —
 * это и есть главное здесь. Чужой человек кладёт файл обычной формой на странице приёма, без
 * пропуска, по одному лишь адресу; телефон забирает файл `GET /u/<box>/take` и подтверждает
 * получение (`POST /u/<box>/ack`), после чего ящик пустеет.
 *
 * Байты приезжают **не зашифрованными** и без кадра: ключа у чужого браузера нет и быть не может,
 * а форма шлёт файл как есть. Имя и тип приходят заголовками.
 */
class RelayDropInbox(
    private val relayUrl: String,
    /** Пропуск устройства в аккаунте (#473): общего пароля приложения больше нет, у каждого свой. */
    private val pass: () -> String?,
) : DropInbox {

    override suspend fun open(): DropInboxBox? = withContext(Dispatchers.IO) {
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
            // Адрес ящика называет сервер. Придумывать его на клиенте нельзя: человек показал бы
            // другу ссылку на ящик, которого не существует, а телефон ждал бы файла в пустоте —
            // и каждая такая попытка навсегда съедала бы одну из пяти доступных ячеек.
            val answer = org.json.JSONObject(body)
            val box = answer.optString("box").takeIf { it.isNotBlank() } ?: return@runCatching null
            val link = answer.optString("url").takeIf { it.isNotBlank() }
                ?: dropInboxLink(base, box) ?: return@runCatching null
            DropInboxBox(box, link)
        }.getOrNull()
    }

    /**
     * Круг ожидания. Три исхода названы раздельно (#114): сервер отвечает `204`, когда никто ничего
     * не положил, — это норма; всё прочее (нет сети, чужой ответ) — отказ, и он доезжает до
     * человека словами, а не вечным «Ждём файл…». Паузу между кругами держит экран.
     *
     * Кадр здесь не разбирается: файл кладёт чужой браузер обычной формой, поэтому байты приходят
     * как есть, а имя и тип — заголовками.
     */
    override suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait =
        withContext(Dispatchers.IO) {
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
                // Имя дал чужой человек — на диск оно идёт только через чистую проверку.
                val name = dropFileName(decodeName(c.getHeaderField("X-File-Name")))
                val mime = c.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                c.disconnect()

                val path = target(name)
                File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                // Забрали — освобождаем ящик: иначе тот же файл приехал бы ещё раз на следующем
                // круге ожидания, и человек получил бы его дважды.
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

    /** Кода ответа человек не понимает — он понимает, что случилось и что делать. */
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
            connectTimeout = 10_000
            readTimeout = 20_000
            pass()?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
}
