package com.point.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** Result of an HTTP call: status [code] and response [body] text (empty if none). */
data class HttpResult(val code: Int, val body: String)

/**
 * A minimal JSON-over-HTTP POST behind an interface, so the LLM clients' request
 * building and response parsing become unit-testable with a fake transport — no
 * real network, no Robolectric. The single real implementation
 * ([UrlConnectionHttpJson]) holds the HttpURLConnection boilerplate that used to
 * be copied into every provider client.
 */
interface HttpJson {
    /**
     * POST [body] (a JSON string) to [url] with [headers], on top of a JSON content
     * type. Returns the status and response text for BOTH success and error codes,
     * so the caller maps 4xx/5xx to a domain error itself. Throws only on a transport
     * failure — timeout, DNS, no network — which the caller lets bubble to fallback.
     */
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
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, text)
        }
}
