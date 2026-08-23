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

    override suspend fun run(obj: PointObject, prompt: String): ResultObject = run(obj, prompt, emptySet())

    override suspend fun run(obj: PointObject, prompt: String, avoidServices: Set<String>): ResultObject {
        if (providers.isEmpty()) error("AI не настроен — $AI_KEY_HINT")

        // Снимок читает тот, кто видит. В режиме YOLO сильная модель идёт первой всегда
        // (#795): человек попросил лучший результат, а не самый бережный порядок.
        val strongestFirst = obj.mime.startsWith("image/") ||
            runCatching { yolo.enabled() }.getOrDefault(false)
        val ordered = if (strongestFirst) providers.sortedByDescending { it.strongVision } else providers

        // Сначала режим, потом всё остальное: сервис, который режим не пускает, не должен
        // даже пробоваться.
        val level = runCatching { privacy.level() }.getOrDefault(PrivacyLevel.DEFAULT)
        val allowedAll = allowedBy(level, ordered) { promiseOfService(it.serviceId) }
        if (allowedAll.isEmpty() && ordered.any { it.configured }) error(chainClosedBy(level))

        // Виток «сильнее» обходит уже отвечавших (#1010) — но только когда есть кем
        // заменить: повтор той же моделью лучше отказа.
        val fresh = allowedAll.filter { it.configured && it.serviceId !in avoidServices && it.canHandle(obj) }
        val rotating = avoidServices.isNotEmpty() && fresh.isNotEmpty()
        val allowed = if (rotating) fresh else allowedAll
        val errors = mutableListOf<String>()

        // Чужой ответ дословно — для журнала обменов, не для человека (#1236). Цепочка
        // единственная, кто видит отказ каждого сервиса: дальше едет уже сводка, и без
        // этого канала отладочный стенд не узнает, чем сервис ответил на самом деле.
        val serviceSaid = mutableListOf<String>()
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
            if (!network.isAvailable()) error(NO_NETWORK_TEXT)

            try {
                // Короткий предел на попытку живёт не здесь, а в самом транспорте
                // (HttpJson/HttpFiles connectTimeout/readTimeout, #690, #691): молчащий
                // сервис отпускает очередь за секунды, не за полторы минуты, и это
                // верно для любого провайдера в цепочке, кем бы он ни был вызван.
                // Составной исполнитель (ключи человека) обходит своих внутри тем же
                // списком; отменённый обход не воскресает этажом ниже (#1176).
                val result = provider.run(obj, prompt, if (rotating) avoidServices else emptySet())
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
                serviceSaidIn(e)?.let { serviceSaid += "${provider.serviceId}: $it" }
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
        // Сеть могла оборваться ПОСРЕДИ очереди (#1134): каждый сервис тогда падает своим
        // отказом, и человек читал «сервисы капризничают», хотя настоящая причина —
        // интернета нет. Сначала называется то, что он может исправить, — тем же словом,
        // каким Point говорит про сеть до начала действия.
        if (!network.isAvailable()) error(NO_NETWORK_TEXT)
        val said = summariseCloudErrors(errors, WHAT_FAILED)
        if (serviceSaid.isEmpty()) error(said)
        throw CloudChainRefusal(said, serviceSaid.joinToString("\n\n"))
    }

    companion object {

        /** Пока режим не подсказан снаружи: тестам и старым вызовам — прежнее поведение. */
        internal val OPEN_TO_EVERYONE = object : CloudPrivacySettings {
            override fun level() = PrivacyLevel.FREE_FIRST
            override suspend fun setLevel(level: PrivacyLevel) = Unit
        }

        /** Та же, что у облачного чтения: человеку без разницы, кто именно упёрся в лимит. */
        const val FREE_LIMITS_SPENT = FREE_LIMITS_SPENT_TEXT

        /** Глагол этой цепочки для общей сводки отказов (#1237). */
        internal const val WHAT_FAILED = "прочитать"
    }
}
