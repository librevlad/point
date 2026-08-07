package com.point.data

import com.point.core.flow.AI_KEY_HINT
import com.point.core.flow.LlmClient
import com.point.core.flow.ObjectStore
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.flow.UserKeyStore
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import javax.inject.Inject

class UserKeyLlmClient @Inject constructor(
    private val userKeys: UserKeyStore,
    private val http: HttpJson,
    private val store: ObjectStore,
) : LlmClient {

    override val strongVision = true

    override val configured: Boolean get() = userKeys.read()?.apiKey?.isNotBlank() == true

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {

        val config = userKeys.read()
            ?: error("$AI_KEY_HINT — откройте «$SETTINGS_TITLE» на домашнем экране")
        return OpenAiCompatibleClient(
            http,
            store,
            OpenAiProvider("свой ключ", config.baseUrl, config.apiKey, config.model),
        ).run(obj, prompt)
    }
}
