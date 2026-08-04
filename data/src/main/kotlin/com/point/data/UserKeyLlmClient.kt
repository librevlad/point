package com.point.data

import com.point.core.flow.AI_KEY_HINT
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.UserKeyStore
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import javax.inject.Inject

/**
 * The user's own key as an [LlmClient], read at call time (it can change any moment
 * via the key screen). Placed FIRST in the fallback chain: when a key is set it wins
 * (their quota); when it isn't, it steps aside with a clear "set a key" error so the
 * built-in dev providers still serve during development.
 */
class UserKeyLlmClient @Inject constructor(
    private val userKeys: UserKeyStore,
    private val http: HttpJson,
    private val store: ObjectStore,
) : LlmClient {

    override val strongVision = true // the user's own model is their choice — trust it for vision too

    /** Ключ спрашивается заново каждый раз: человек мог ввести его минуту назад (#467). */
    override val configured: Boolean get() = userKeys.read()?.apiKey?.isNotBlank() == true

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        val config = userKeys.read() ?: error("$AI_KEY_HINT (шестерёнка на домашнем экране)")
        return OpenAiCompatibleClient(
            http,
            store,
            OpenAiProvider("свой ключ", config.baseUrl, config.apiKey, config.model),
        ).run(obj, prompt)
    }
}
