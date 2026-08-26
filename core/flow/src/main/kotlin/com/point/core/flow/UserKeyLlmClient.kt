package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject

/**
 * Ключи человека — по одному на сервис (#699). Идут в том порядке, в каком Point
 * обращается к сервисам, и каждый исход запоминается за своим сервисом.
 */
class UserKeyLlmClient(
    private val userKeys: UserKeyStore,
    private val http: HttpJson,
    private val store: ObjectStore,
    private val facts: AiFacts,

    /** Режим приватности сужает и ключи человека: у каждого сервиса своё обещание (#945). */
    private val privacy: CloudPrivacySettings = FallbackLlmClient.OPEN_TO_EVERYONE,

    /** Готовилка кадра — та же, что у остальной цепочки (#1239): без неё снимок не уедет. */
    private val frames: FrameForModel = FrameForModel.NONE,
) : LlmClient {

    override val strongVision = true

    override val configured: Boolean get() = userKeys.keys().mine.isNotEmpty()

    /**
     * Снимок без кадра — сожжённая квота человека (#1239).
     *
     * Ключ человека идёт по снимку первым, и запрос без картинки не бесполезен, а вреден:
     * послушная модель отвечает NO_IMAGE, непослушная сочиняет чтение документа, которого
     * не видела, а бесплатная попытка и до 30 с ожидания уже потрачены. Готовить кадр
     * нечем — значит, снимок этому исполнителю не по силам, и цепочка идёт дальше.
     */
    override fun canHandle(obj: PointObject): Boolean =
        !obj.mime.startsWith("image/") || frames !== FrameForModel.NONE

    override suspend fun run(obj: PointObject, prompt: String): ResultObject = run(obj, prompt, emptySet())

    override suspend fun run(obj: PointObject, prompt: String, avoidServices: Set<String>): ResultObject {
        val all = userKeys.keys().mine
        if (all.isEmpty()) error("$AI_KEY_HINT — откройте «$SETTINGS_TITLE» на домашнем экране")

        val level = runCatching { privacy.level() }.getOrDefault(PrivacyLevel.DEFAULT)
        val mine = allowedBy(level, all) { promiseOfService(it.providerId) }
        if (mine.isEmpty()) error(chainClosedBy(level))

        // Виток «сильнее» обходит уже отвечавшие сервисы и среди ключей человека —
        // но только когда есть кем заменить: повтор лучше отказа (#1010, #1176).
        val freshKeys = mine.filter { it.providerId !in avoidServices }
        val queue = if (avoidServices.isEmpty() || freshKeys.isEmpty()) mine else freshKeys

        val errors = mutableListOf<String>()
        for (key in queue) {
            try {
                val result = clientFor(key).run(obj, prompt)
                facts.remember(key.providerId, AiOutcome.ANSWERED)

                // Кто ответил — часть ответа (#1127): без имени ответ по ключу человека
                // не участвовал в обходе следующего витка (#1176).
                return result.copy(metadata = result.metadata + (META_ANSWERED_BY to key.providerId))
            } catch (e: Exception) {
                facts.remember(key.providerId, aiOutcomeOf(e))
                errors += e.message ?: e.javaClass.simpleName
            }
        }
        error(errors.map { it.substringBefore('\n').take(120) }.distinct().take(2).joinToString("; "))
    }

    private fun clientFor(key: UserAiKey): OpenAiCompatibleClient {
        val call = aiCall(key)
        return OpenAiCompatibleClient(
            http,
            store,
            OpenAiProvider(
                label = nameOf(key.providerId),
                baseUrl = call.baseUrl,
                apiKey = call.apiKey,
                model = call.model,

                // Ключ человека — его выбор: не Point решает, что этой моделью
                // картинку показывать нельзя.
                vision = true,
                strongVision = true,
                id = key.providerId,
            ),
            frames,
        )
    }

    private fun nameOf(providerId: String): String =
        AI_PROVIDERS.firstOrNull { it.id == providerId }?.name ?: OWN_SERVICE_NAME
}
