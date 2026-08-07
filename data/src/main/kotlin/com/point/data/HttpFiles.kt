package com.point.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

sealed interface FormPart {
    val name: String

    data class Field(override val name: String, val value: String) : FormPart

    class Binary(
        override val name: String,
        val fileName: String,
        val contentType: String,
        val bytes: ByteArray,
    ) : FormPart
}

interface HttpFiles {

    suspend fun postMultipart(url: String, headers: Map<String, String>, parts: List<FormPart>): HttpResult

    suspend fun get(url: String, headers: Map<String, String>): HttpResult
}

class UrlConnectionHttpFiles @Inject constructor() : HttpFiles {

    override suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        parts: List<FormPart>,
    ): HttpResult = withContext(Dispatchers.IO) {
        val boundary = "----point${System.nanoTime()}"
        val body = encode(parts, boundary)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            pointHeaders(
                mapOf(
                    "Content-Type" to "multipart/form-data; boundary=$boundary",
                    "Accept" to "application/json",
                ),
                headers,
            ).forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body) }
        conn.read()
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResult =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                pointHeaders(mapOf("Accept" to "application/json"), headers)
                    .forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.read()
        }

    private fun HttpURLConnection.read(): HttpResult {
        val status = responseCode
        val text = (if (status in 200..299) inputStream else errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResult(status, text)
    }

    private fun encode(parts: List<FormPart>, boundary: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        parts.forEach { part ->
            write("--$boundary\r\n")
            when (part) {
                is FormPart.Field -> {
                    write("Content-Disposition: form-data; name=\"${part.name}\"\r\n\r\n")
                    write(part.value)
                }
                is FormPart.Binary -> {
                    write(
                        "Content-Disposition: form-data; name=\"${part.name}\"; " +
                            "filename=\"${part.fileName}\"\r\n" +
                            "Content-Type: ${part.contentType}\r\n\r\n",
                    )
                    out.write(part.bytes)
                }
            }
            write("\r\n")
        }
        write("--$boundary--\r\n")
        return out.toByteArray()
    }
}
