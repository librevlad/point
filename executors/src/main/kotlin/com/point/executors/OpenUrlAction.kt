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
import javax.inject.Inject

class OpenUrlCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "link"
    // Ссылку открывает браузер — шаг Point кончается чужим экраном (#1131).
    override val meta = CapabilityMeta(priority = 20, handsOff = true)
    override fun label(state: ObjectState) = "Открыть ссылку"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.URL || state.has(Feature.HAS_URL)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    // Снимок уже прочитан, а ссылки в нём нет — читать второй раз незачем, и обещать это
    // человеку нельзя (#792). Причина остаётся только там, где чтение и правда поможет.
    override fun missing(state: ObjectState) =
        if (state.kind == ObjectKind.IMAGE && !state.has(Feature.HAS_TEXT)) {
            "сначала распознайте текст"
        } else {
            null
        }

    companion object { val ID = CapabilityId("open-url") }
}

class OpenUrlRealizer @Inject constructor(
    private val extractor: com.point.core.flow.EntityExtractor,
    private val opener: UrlOpener,
) : Realizer {
    override val capabilityId = OpenUrlCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

                // Общий порядок: знание (entity.url) → сам объект-ссылка → текст.
                // Свой файл-путь отвечал «Ссылка не найдена» рядом с «Нашёл ссылку»
                // на узле ссылки из QR (скрин владельца 2026-08-09).
                val url = com.point.core.flow.firstEntity(extractor, input, com.point.core.flow.EntityType.URL)
                    ?: error("Ссылка не найдена")
                opener.open(url)
                ActionResult.Done("Открываю: $url")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть", recoverable = true) }
        }
}
