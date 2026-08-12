package com.point.data

import com.point.core.flow.AI_KEY_HINT
import com.point.core.flow.AiFacts
import com.point.core.flow.AiOutcome
import com.point.core.flow.LlmClient
import com.point.core.flow.NetworkAvailability
import com.point.core.flow.YoloMode
import com.point.core.flow.aiOutcomeOf
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import javax.inject.Inject

class FallbackLlmClient @Inject constructor(
    private val providers: List<@JvmSuppressWildcards LlmClient>,

    private val facts: AiFacts,

    private val network: NetworkAvailability,

    private val yolo: YoloMode = YoloMode.OFF,
) : LlmClient {

    override val configured: Boolean get() = providers.any { it.configured }

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        if (providers.isEmpty()) error("AI не настроен — $AI_KEY_HINT")

        // Снимок читает тот, кто видит. В режиме YOLO сильная модель идёт первой всегда
        // (#795): человек попросил лучший результат, а не самый бережный порядок.
        val strongestFirst = obj.mime.startsWith("image/") ||
            runCatching { yolo.enabled() }.getOrDefault(false)
        val ordered = if (strongestFirst) providers.sortedByDescending { it.strongVision } else providers
        val errors = mutableListOf<String>()
        var considered = 0
        var skippedUnconfigured = 0
        for (provider in ordered) {

            // Ненастроенный провайдер не пытается и не шумит в диагноз: без него
            // «нет сети» остаётся «нет сети», а не «задайте ключ; resolve host…»
            // (живой прогон 2026-08-09, offline).
            if (!provider.configured) {
                skippedUnconfigured++
                continue
            }
            if (!provider.canHandle(obj)) continue
            considered++

            // Перед выходом наружу — спросить телефон, есть ли сеть вообще (#690,
            // #691). Нашёлся хоть один настоящий кандидат, и только тогда: без ключей
            // идти наружу всё равно было не за чем, а спрашивать раньше — рано.
            // Офлайн все провайдеры в цепочке одинаково молчат — ждать каждого по
            // очереди значит тратить минуты на то, что телефон уже знает сам.
            if (!network.isAvailable()) error(NO_NETWORK_MESSAGE)

            try {
                // Короткий предел на попытку живёт не здесь, а в самом транспорте
                // (HttpJson/HttpFiles connectTimeout/readTimeout, #690, #691): молчащий
                // сервис отпускает очередь за секунды, не за полторы минуты, и это
                // верно для любого провайдера в цепочке, кем бы он ни был вызван.
                val result = provider.run(obj, prompt)
                facts.remember(provider.serviceId, AiOutcome.ANSWERED)
                return result
            } catch (e: Exception) {

                // Исход обращения помнит сам сервис: экран ключей показывает
                // последний настоящий факт, а не догадку (#699).
                facts.remember(provider.serviceId, aiOutcomeOf(e))
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        if (considered == 0) {
            if (skippedUnconfigured > 0 && errors.isEmpty()) {
                error("AI не настроен — $AI_KEY_HINT")
            }

            val needed = if (obj.mime.startsWith("audio/") || obj.mime == "application/ogg") {
                "с поддержкой аудио"
            } else {
                "с поддержкой изображений"
            }
            error("Для этого объекта нет подходящей AI-модели — $AI_KEY_HINT $needed")
        }
        error(summarise(errors))
    }

    private fun summarise(errors: List<String>): String = when {
        errors.isNotEmpty() && errors.all { it.isNetworkError() } -> NO_NETWORK_MESSAGE
        errors.isNotEmpty() && errors.all { it.isQuotaError() } ->
            FREE_LIMITS_SPENT

        // Живая охота 11.08.2026: на экране всплывало «AI недоступен». Весь остальной
        // Point говорит про модель и чтение, а не аббревиатурой — человеку она ничего
        // не объясняет.
        else -> "Модель недоступна — " +
            errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
    }

    companion object {

        /** Одна формулировка на всех, кто её показывает и проверяет. */
        const val NO_NETWORK_MESSAGE = "Модель недоступна — нет подключения к интернету"

        /** Та же, что у облачного чтения: человеку без разницы, кто именно упёрся в лимит. */
        const val FREE_LIMITS_SPENT = "Бесплатные лимиты чтения исчерпаны — вернитесь позже, платить не идём"
    }
}
