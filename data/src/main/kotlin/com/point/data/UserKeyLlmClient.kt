package com.point.data

import com.point.core.flow.AI_KEY_HINT
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.AiFacts
import com.point.core.flow.AiOutcome
import com.point.core.flow.LlmClient
import com.point.core.flow.OWN_SERVICE_NAME
import com.point.core.flow.ObjectStore
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserKeyStore
import com.point.core.flow.aiCall
import com.point.core.flow.aiOutcomeOf
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import javax.inject.Inject

/**
 * Ключи человека — по одному на сервис (#699). Идут в том порядке, в каком Point
 * обращается к сервисам, и каждый исход запоминается за своим сервисом.
 */
class UserKeyLlmClient @Inject constructor(
    private val userKeys: UserKeyStore,
    private val http: HttpJson,
    private val store: ObjectStore,
    private val facts: AiFacts,
) : LlmClient {

    override val strongVision = true

    override val configured: Boolean get() = userKeys.keys().mine.isNotEmpty()

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        val mine = userKeys.keys().mine
        if (mine.isEmpty()) error("$AI_KEY_HINT — откройте «$SETTINGS_TITLE» на домашнем экране")

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
