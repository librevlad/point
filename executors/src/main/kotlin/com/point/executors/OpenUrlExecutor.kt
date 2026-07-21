package com.point.executors

import com.point.core.flow.Executor
import com.point.core.flow.UrlOpener
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Opens a link. Accepts a `text/uri-list` object outright, or a TEXT object once
 * async enrichment has flagged [Feature.HAS_URL] — a progressive-disclosure
 * bubble that appears AFTER the first paint.
 */
class OpenUrlExecutor @Inject constructor(
    private val opener: UrlOpener,
) : Executor {
    override val id = ExecutorId("open-url")
    override val order = 20
    override val icon = "link"
    override fun title(state: ObjectState) = "Открыть"

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.URL || state.has(Feature.HAS_URL)

    override fun produces(state: ObjectState) = state // terminal

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = firstUrl(input) ?: error("Ссылка не найдена")
                opener.open(url)
                ExecutorResult.Done("Открываю: $url")
            }.getOrElse { ExecutorResult.Failure(it.message ?: "Не удалось открыть", recoverable = true) }
        }

    private fun firstUrl(input: PointObject): String? {
        val text = File(input.uri.value).takeIf { it.exists() }?.readText().orEmpty()
        return URL_REGEX.find(text)?.value
    }

    private companion object {
        val URL_REGEX = Regex("""https?://\S+""")
    }
}
