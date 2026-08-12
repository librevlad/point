package com.point.core.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpResult(val code: Int, val body: String)

interface HttpJson {

    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult
}

class UrlConnectionHttpJson() : HttpJson {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true

                // Короткий предел на попытку (#690, #691): NetworkAvailability на входе
                // ловит полное офлайн сразу, а эти числа — предел на один настоящий
                // запрос, чтобы молчащий сервис не держал очередь провайдеров минутами.
                connectTimeout = 10_000
                readTimeout = 30_000
                pointHeaders(mapOf("Content-Type" to "application/json; charset=utf-8"), headers)
                    .forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.callClosingOnCancel {
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = responseCode
                val text = (if (code in 200..299) inputStream else errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                HttpResult(code, text)
            }
        }
}
