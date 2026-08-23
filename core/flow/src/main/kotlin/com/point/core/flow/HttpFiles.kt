package com.point.core.flow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

/**
 * Отправка файла чужому сервису: запись голоса уходит на расшифровку.
 *
 * Запроса за файлом (`get`) у шва нет (#1252). Он жил ради опроса задачи у облачного чтения
 * со слоем слов — контура, до которого человек не мог дойти ни одним нажатием; вместе с
 * читалками ушёл и он. Метод, которого не зовёт продукт, — то же обещание без нажатия:
 * следующий читатель чинит путь, которого нет. Понадобится опрос задачи снова — он вернётся
 * вместе с тем, кто его зовёт.
 */
interface HttpFiles {

    suspend fun postMultipart(url: String, headers: Map<String, String>, parts: List<FormPart>): HttpResult
}

class UrlConnectionHttpFiles() : HttpFiles {

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

            // См. HttpJson.kt — короткий предел на попытку (#690, #691). Чтение чуть
            // щедрее, чем у HttpJson: сюда ходят вложения (снимок, запись), им нужно
            // больше времени на отправку.
            connectTimeout = 10_000
            readTimeout = 45_000
            pointHeaders(
                mapOf(
                    "Content-Type" to "multipart/form-data; boundary=$boundary",
                    "Accept" to "application/json",
                ),
                headers,
            ).forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.callClosingOnCancel {
            outputStream.use { it.write(body) }
            read()
        }
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
