package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal fun formatEntities(entities: List<Entity>): String {
    fun values(type: EntityType) =
        entities.filter { it.type == type }.map { it.value.trim() }.filter { it.isNotBlank() }.distinct()

    return buildString {
        appendSection("Телефоны", values(EntityType.PHONE))
        appendSection("Почты", values(EntityType.EMAIL))
        appendSection("Ссылки", values(EntityType.URL))
        appendSection("Адреса", values(EntityType.ADDRESS))
    }.trim()
}

private fun StringBuilder.appendSection(title: String, items: List<String>) {
    if (items.isEmpty()) return
    if (isNotEmpty()) append("\n")
    append(title).append(":\n")
    items.forEach { append(it).append("\n") }
}

class ExtractAllCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "list"

    override val meta = CapabilityMeta(priority = 30, latency = Latency.FAST)
    override fun label(state: ObjectState) = "Собрать данные"
    override fun accepts(state: ObjectState) =
        state.has(Feature.HAS_PHONE) || state.has(Feature.HAS_EMAIL) ||
            state.has(Feature.HAS_URL) || state.has(Feature.HAS_ADDRESS)
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("extract-all") }
}

class ExtractAllRealizer @Inject constructor(
    private val store: ObjectStore,
    private val extractor: EntityExtractor,
) : Realizer {
    override val capabilityId = ExtractAllCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = entitySourceText(input)
                val list = formatEntities(extractor.extract(text))
                if (list.isBlank()) {
                    ActionResult.Failure("Не удалось собрать данные", recoverable = true)
                } else {
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(list)
                    ActionResult.Success(ResultObject(ObjectKind.TEXT, "text/plain", ref, mapOf("op" to "extract-all")))
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось собрать данные", recoverable = true) }
        }
}
