package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.UrlOpener
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class OpenUrlCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "link"
    override val meta = CapabilityMeta(priority = 20)
    override fun label(state: ObjectState) = "Открыть ссылку"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.URL || state.has(Feature.HAS_URL)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    override fun missing(state: ObjectState) =
        if (state.kind == ObjectKind.IMAGE) "сначала распознайте текст" else null

    companion object { val ID = CapabilityId("open-url") }
}

class OpenUrlRealizer @Inject constructor(
    private val opener: UrlOpener,
) : Realizer {
    override val capabilityId = OpenUrlCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = firstUrl(input) ?: error("Ссылка не найдена")
                opener.open(url)
                ActionResult.Done("Открываю: $url")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть", recoverable = true) }
        }

    private fun firstUrl(input: PointObject): String? {
        val text = File(input.uri.value).takeIf { it.exists() }?.readText().orEmpty()
        return URL_REGEX.find(text)?.value
    }

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
    }
}
