package com.point.bot

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The real Telegram Bot API over HttpURLConnection (#92) — zero SDK, same discipline as
 * the desktop's hand-rolled http. Long-polling ([getUpdates]) means no webhook/server:
 * the bot runs from anywhere, even behind NAT, on the owner's PC.
 */
class HttpTelegramApi(private val token: String) : TelegramApi {

    private val base = "https://api.telegram.org/bot$token"
    private val fileBase = "https://api.telegram.org/file/bot$token"

    /** Long-poll for updates since [offset]; returns raw JSON for [parseUpdates]. */
    suspend fun getUpdates(offset: Long, timeoutSec: Int = 25): String = withContext(Dispatchers.IO) {
        val url = URL("$base/getUpdates?timeout=$timeoutSec&offset=$offset")
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = (timeoutSec + 10) * 1000
        }
        val body = c.inputStream.bufferedReader().readText()
        c.disconnect()
        body
    }

    override suspend fun sendMessage(chatId: Long, text: String, keyboard: String?): Unit = withContext(Dispatchers.IO) {
        val form = buildString {
            append("chat_id=").append(chatId)
            append("&text=").append(enc(text.take(4096)))
            if (keyboard != null) append("&reply_markup=").append(enc(keyboard))
        }
        postForm("$base/sendMessage", form)
    }

    override suspend fun sendDocument(chatId: Long, file: File, caption: String?): Unit = withContext(Dispatchers.IO) {
        postMultipartDocument("$base/sendDocument", chatId, file, caption)
    }

    override suspend fun downloadFile(fileId: String, target: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val meta = URL("$base/getFile?file_id=${enc(fileId)}").readText()
            val path = JSONObject(meta).getJSONObject("result").getString("file_path")
            val c = (URL("$fileBase/$path").openConnection() as HttpURLConnection).apply { readTimeout = 60_000 }
            target.parentFile?.mkdirs()
            c.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            true
        }.getOrDefault(false)
    }

    override suspend fun answerCallback(callbackId: String): Unit = withContext(Dispatchers.IO) {
        runCatching { postForm("$base/answerCallbackQuery", "callback_query_id=${enc(callbackId)}") }
        Unit
    }

    private fun postForm(url: String, form: String) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        c.outputStream.use { it.write(form.toByteArray()) }
        c.responseCode
        c.disconnect()
    }

    private fun postMultipartDocument(url: String, chatId: Long, file: File, caption: String?) {
        val boundary = "----point${System.nanoTime()}"
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        c.outputStream.use { out ->
            fun field(name: String, value: String) {
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n").toByteArray())
            }
            field("chat_id", chatId.toString())
            if (caption != null) field("caption", caption)
            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray())
            file.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        c.responseCode
        c.disconnect()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** The highest update_id in a getUpdates response, or null when empty (for the next offset). */
fun highestUpdateId(json: String): Long? =
    runCatching { JSONObject(json).optJSONArray("result") }.getOrNull()
        ?.let { arr: JSONArray -> (0 until arr.length()).map { arr.getJSONObject(it).optLong("update_id") }.maxOrNull() }
