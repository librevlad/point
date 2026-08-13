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
) : LlmClient {

    override val strongVision = true

    override val configured: Boolean get() = userKeys.keys().mine.isNotEmpty()

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        val all = userKeys.keys().mine
        if (all.isEmpty()) error("$AI_KEY_HINT — откройте «$SETTINGS_TITLE» на домашнем экране")

        val level = runCatching { privacy.level() }.getOrDefault(PrivacyLevel.DEFAULT)
        val mine = allowedBy(level, all) { promiseOfService(it.providerId) }
        if (mine.isEmpty()) error(chainClosedBy(level))

        val errors = mutableListOf<String>()
        for (key in mine) {
            try {
                val result = clientFor(key).run(obj, prompt)
                facts.remember(key.providerId, AiOutcome.ANSWERED)
                return result
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
        )
    }

    private fun nameOf(providerId: String): String =
        AI_PROVIDERS.firstOrNull { it.id == providerId }?.name ?: OWN_SERVICE_NAME
}
