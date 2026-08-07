package com.point.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

data class HttpResult(val code: Int, val body: String)

interface HttpJson {

    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult
}

class UrlConnectionHttpJson @Inject constructor() : HttpJson {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResult =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 60_000
                pointHeaders(mapOf("Content-Type" to "application/json; charset=utf-8"), headers)
                    .forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, text)
        }
}
