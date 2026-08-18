package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject

class FallbackLlmClient(
    private val providers: List<@JvmSuppressWildcards LlmClient>,

    private val facts: AiFacts,

    private val network: NetworkAvailability,

    private val yolo: YoloMode = YoloMode.OFF,

    /**
     * Режим приватности сужает цепочку, а не обнуляет её (#945).
     *
     * У каждого сервиса своё обещание про присланное. В режиме «Не учатся на моём» идут
     * только те, кто это обещал письменно, — остальные пропускаются с названной причиной.
     */
    private val privacy: CloudPrivacySettings = OPEN_TO_EVERYONE,
) : LlmClient {

    override val configured: Boolean get() = providers.any { it.configured }

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        if (providers.isEmpty()) error("AI не настроен — $AI_KEY_HINT")

        // Снимок читает тот, кто видит. В режиме YOLO сильная модель идёт первой всегда
        // (#795): человек попросил лучший результат, а не самый бережный порядок.
        val strongestFirst = obj.mime.startsWith("image/") ||
            runCatching { yolo.enabled() }.getOrDefault(false)
        val ordered = if (strongestFirst) providers.sortedByDescending { it.strongVision } else providers

        // Сначала режим, потом всё остальное: сервис, который режим не пускает, не должен
        // даже пробоваться.
        val level = runCatching { privacy.level() }.getOrDefault(PrivacyLevel.DEFAULT)
        val allowed = allowedBy(level, ordered) { promiseOfService(it.serviceId) }
        if (allowed.isEmpty() && ordered.any { it.configured }) error(chainClosedBy(level))
        val errors = mutableListOf<String>()
        var considered = 0
        var skippedUnconfigured = 0
        for (provider in allowed) {

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

                // Кто ответил — часть ответа (#1127). Знание, добытое облаком, иначе
                // приходит в Graph безымянным: «понято по смыслу» — и всё, а каким
                // сервисом и можно ли сравнить его со вторым, сказать нечем.
                return if (provider.serviceId.isBlank()) {
                    result
                } else {
                    result.copy(metadata = result.metadata + (META_ANSWERED_BY to provider.serviceId))
                }
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
            error("Такой объект прочитать нечем — $AI_KEY_HINT $needed")
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
        else -> "Прочитать не вышло — " +
            errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; ")
    }

    companion object {

        /** Пока режим не подсказан снаружи: тестам и старым вызовам — прежнее поведение. */
        internal val OPEN_TO_EVERYONE = object : CloudPrivacySettings {
            override fun level() = PrivacyLevel.FREE_FIRST
            override suspend fun setLevel(level: PrivacyLevel) = Unit
        }

        /** Одна формулировка на всех, кто её показывает и проверяет. */
        const val NO_NETWORK_MESSAGE = "Нет интернета — прочитать не получится"

        /** Та же, что у облачного чтения: человеку без разницы, кто именно упёрся в лимит. */
        const val FREE_LIMITS_SPENT = FREE_LIMITS_SPENT_TEXT
    }
}
