package com.point.data

import com.point.core.flow.AiKeyProbe
import com.point.core.flow.KeyCheck
import com.point.core.flow.UserAiConfig
import com.point.core.flow.keyFingerprint
import com.point.core.flow.keyProbeFailure
import com.point.core.flow.keyRejectionReason
import com.point.core.flow.providerForBaseUrl
import org.json.JSONObject

/**
 * «Проверить ключ» — один настоящий короткий запрос к провайдеру (#447).
 *
 * **Той же дорогой, что и настоящее действие.** Ключ человека уходит в сеть ровно одним путём —
 * [UserKeyLlmClient] → [OpenAiCompatibleClient], — и проверка обязана идти им же. Проверка, которая
 * ходит куда-то ещё (скажем, дёргает `/models`), отвечает на другой вопрос: она скажет «ключ
 * годится», а действие с AI всё равно откажет, потому что модель не та или адрес не тот. Такая
 * проверка хуже её отсутствия: она выдаёт человеку ложное «всё в порядке».
 *
 * **Ответ не сглаживается.** 401 и 429 — разные новости: первую чинит сам человек (опечатка),
 * вторая пройдёт сама. Текст провайдера прикладывается, потому что он часто объясняет причину
 * лучше нас ([keyRejectionReason]).
 *
 * Ключ не пишется ни в лог, ни в сообщение об ошибке: он живёт только в заголовке запроса.
 */
class HttpAiKeyProbe(
    private val http: HttpJson,
    /** Часы отдельно от кода — чтобы «за 1,2 с» можно было проверить, а не засечь. */
    private val now: () -> Long = System::currentTimeMillis,
) : AiKeyProbe {

    override suspend fun check(config: UserAiConfig): KeyCheck {
        val fingerprint = keyFingerprint(config)
        val label = providerForBaseUrl(config.baseUrl)?.name ?: "Сервис"
        val key = config.apiKey.trim()
        if (key.isEmpty()) return KeyCheck.Rejected("Ключ пуст — проверять нечего.", fingerprint)
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            return KeyCheck.Rejected("Адрес сервиса пуст — некуда стучаться.", fingerprint)
        }

        val started = now()
        val response = runCatching {
            http.post(
                "$base/chat/completions",
                mapOf("Authorization" to "Bearer $key"),
                probeBody(config.model),
            )
        }.getOrElse { failure ->
            // Транспортный отказ (нет сети, DNS, таймаут) — это не «ключ плохой», и так и сказано.
            return KeyCheck.Rejected(
                keyProbeFailure(label, failure.message ?: failure::class.simpleName.orEmpty()),
                fingerprint,
            )
        }
        val took = (now() - started).coerceAtLeast(0)

        if (response.code !in 200..299) {
            return KeyCheck.Rejected(keyRejectionReason(label, response.code, response.body), fingerprint)
        }
        // 200 ещё не значит «модель ответила»: часть шлюзов отдаёт 200 с телом-ошибкой. Судим по
        // тому же признаку, по которому судит настоящий клиент, — есть ли `choices`.
        val answered = runCatching {
            (JSONObject(response.body).optJSONArray("choices")?.length() ?: 0) > 0
        }.getOrDefault(false)
        return if (answered) {
            KeyCheck.Works(model = config.model.trim(), tookMs = took, checked = fingerprint)
        } else {
            val tail = response.body.trim().replace(Regex("\\s+"), " ").take(160)
            KeyCheck.Rejected(
                "$label ответил, но без модели — ключ дошёл, а ответа нет." +
                    if (tail.isEmpty()) "" else " Ответ: $tail",
                fingerprint,
            )
        }
    }

    /** Самый дешёвый настоящий запрос: одно слово туда, один токен обратно. */
    private fun probeBody(model: String): String =
        JSONObject()
            .put("model", model.trim())
            .put(
                "messages",
                org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
            )
            .put("max_tokens", 1)
            .toString()
}
