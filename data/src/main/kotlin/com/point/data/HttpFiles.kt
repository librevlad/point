package com.point.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Одна часть multipart-формы: либо текстовое поле, либо файл с байтами.
 *
 * Существует, потому что оба бесплатных читателя страницы принимают документ **только**
 * `multipart/form-data` (Unstructured `-F files=@…`, LlamaParse `-F file=@…`), а [HttpJson] умеет
 * ровно один способ — JSON-строкой. Класть картинку в JSON через Base64 эти два сервиса не умеют,
 * и «почти работающий» запрос молча вернул бы 400 вместо чтения (#280).
 */
sealed interface FormPart {
    val name: String

    /** Обычное поле формы: `-F coordinates=true`. */
    data class Field(override val name: String, val value: String) : FormPart

    /**
     * Файл: `-F files=@page.jpg`. Байты, а не строка — снимок обязан доехать
     * побайтово; текстовое поле его бы испортило перекодировкой.
     */
    class Binary(
        override val name: String,
        val fileName: String,
        val contentType: String,
        val bytes: ByteArray,
    ) : FormPart
}

/**
 * Загрузка файла и опрос задачи за интерфейсом — второй ШОВ сети рядом с [HttpJson].
 *
 * Отдельный контракт, а не новый метод в [HttpJson]: у [HttpJson] уже есть с десяток подделок в
 * тестах, и добавленный туда метод либо сломал бы их все, либо приехал бы с реализацией по
 * умолчанию, которая бросает, — то есть подделка молча соврала бы про свои возможности.
 *
 * Как и [HttpJson], возвращает [HttpResult] и на 4xx/5xx тоже: 402 (нужна карта) и 429 (кончился
 * бесплатный лимит) — это законный ответ, по которому цепочка идёт к следующему слою, а не авария.
 */
interface HttpFiles {

    /** POST формы с файлом. Бросает только на транспортном сбое — таймаут, DNS, нет сети. */
    suspend fun postMultipart(url: String, headers: Map<String, String>, parts: List<FormPart>): HttpResult

    /** GET — опрос асинхронной задачи (LlamaParse отвечает `job_id`, результат забирается позже). */
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
            readTimeout = 120_000 // чтение страницы облаком — это не чат, ответ приходит небыстро
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            // Без него Groq отвечает 403 — см. [POINT_USER_AGENT].
            setRequestProperty("User-Agent", POINT_USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
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
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", POINT_USER_AGENT)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.read()
        }

    private fun HttpURLConnection.read(): HttpResult {
        val status = responseCode
        val text = (if (status in 200..299) inputStream else errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResult(status, text)
    }

    /** Тело формы целиком в памяти: страница — это единицы мегабайт, стрим тут не окупается. */
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
