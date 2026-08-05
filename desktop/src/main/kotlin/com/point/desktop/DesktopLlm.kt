package com.point.desktop

import com.point.core.flow.LlmClient
import com.point.core.flow.jsonObject
import com.point.core.flow.parseJson
import com.point.core.flow.str
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI на компьютере (#585) — по OpenAI-совместимой ручке, своя, а не портированная с телефона.
 *
 * Телефонные клиенты живут в `:data` и разбирают ответ через `org.json` — часть Android SDK,
 * которой на голой JVM нет. Тащить сюда чужую библиотеку ради одного разбора не за что: у Point
 * свой разборщик JSON в `:core:flow`, и он ровно на это и рассчитан.
 *
 * **Совместимый интерфейс, а не конкретный провайдер.** Один и тот же вызов работает у OpenRouter,
 * Groq, Cerebras, локальной модели — меняется только адрес и имя модели в `~/.point-pc/config`.
 * Это то же решение, что на телефоне (`OpenAiCompatibleClient`): выбор провайдера — дело человека,
 * а не приложения, и бесплатные квоты живут дольше, когда их можно менять руками.
 *
 * Ключ **не компилируется в артефакт никогда** — он лежит только в конфиге на машине человека.
 */
class DesktopLlmClient(
    private val config: () -> AiConfig,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
) : LlmClient {

    /**
     * Картинку этот клиент не берёт.
     *
     * Зрячие модели по совместимой ручке требуют своего формата вложения, и «отправить и
     * посмотреть» тут не работает: часть провайдеров молча отвечает текстом «я не вижу
     * изображения», и это выглядит как ответ, а не как отказ. Пока компьютер честно говорит, что
     * не умеет, — человек несёт снимок на телефон, где зрячая цепочка построена и замерена.
     */
    override fun canHandle(obj: PointObject): Boolean = obj.state.kind != ObjectKind.IMAGE

    override val configured: Boolean get() = config().key.isNotBlank()

    override suspend fun run(obj: PointObject, prompt: String): ResultObject = withContext(Dispatchers.IO) {
        val cfg = config()
        require(cfg.key.isNotBlank()) { "Ключ AI не задан" }
        val text = readText(obj)
        val body = jsonRequest(cfg.model, prompt, text)
        val answer = post(cfg.url, cfg.key, body)
        val file = File.createTempFile("pc-ai-", ".txt").apply { writeText(answer) }
        ResultObject(
            type = ObjectKind.TEXT,
            mime = "text/plain",
            uri = ScratchRef(file.absolutePath),
            metadata = mapOf("name" to "Ответ AI"),
        )
    }

    /** Объект приезжает файлом; модели нужен его текст. Не текст — это не к нам ([canHandle]). */
    private fun readText(obj: PointObject): String {
        val file = File(obj.uri.value)
        require(file.isFile) { "Файла объекта нет на диске" }
        // Хвост длинного документа режется здесь, а не на сервере провайдера: обрезка по
        // границе байт может разорвать символ, и модель получит мусор в последнем слове.
        return file.readText().take(MAX_CHARS)
    }

    private fun jsonRequest(model: String, prompt: String, text: String): String {
        val message = jsonObject("role" to "user", "content" to (prompt + "\n\n" + text))
        return """{"model":"${escape(model)}","messages":[$message]}"""
    }

    private fun post(url: String, key: String, body: String): String {
        val connection = URL(url.trimEnd('/')).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val reply = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(code in 200..299) { refusal(code, reply) }
            answerOf(reply) ?: error("Сервис ответил, но без ответа")
        } finally {
            connection.disconnect()
        }
    }

    /** Отказ словами, а не кодом: человек читает его в карточке исхода, как и на телефоне. */
    private fun refusal(code: Int, reply: String): String = when (code) {
        401, 403 -> "Ключ AI не подошёл — проверьте его в ~/.point-pc/config"
        402 -> "У этого ключа кончилась бесплатная квота"
        429 -> "Сервис просит подождать — слишком много запросов подряд"
        in 500..599 -> "Сервис AI сейчас не отвечает"
        else -> "Сервис AI отказал (" + code + ")" + reply.take(200).let { if (it.isBlank()) "" else ": $it" }
    }

    /** `choices[0].message.content` — общая форма ответа у всех совместимых ручек. */
    private fun answerOf(reply: String): String? {
        val json = parseJson(reply)
        val choices = (json as? com.point.core.flow.JsonValue.Obj)?.fields?.get("choices")
        val first = (choices as? com.point.core.flow.JsonValue.Arr)?.items?.firstOrNull()
        val message = (first as? com.point.core.flow.JsonValue.Obj)?.fields?.get("message")
        return message.str("content")?.takeIf { it.isNotBlank() }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        /** Сколько знаков документа уходит модели. Дальше начинается плата за то, что не читают. */
        const val MAX_CHARS = 120_000
    }
}

/**
 * Что нужно знать, чтобы позвать AI с компьютера. Живёт в `~/.point-pc/config` рядом с именем
 * машины и адресом сервера — там же, где человек уже правит настройки ПК.
 */
data class AiConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
    val model: String = DEFAULT_MODEL,
) {
    companion object {
        /** OpenRouter: у него самый широкий выбор бесплатных моделей и общая совместимая ручка. */
        const val DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions"

        /** Бесплатная модель по умолчанию — платить за первый запуск человек не должен. */
        const val DEFAULT_MODEL = "meta-llama/llama-3.3-70b-instruct:free"
    }
}
