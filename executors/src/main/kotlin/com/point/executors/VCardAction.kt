package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Viewer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

internal fun vCardSummary(text: String): List<String> {
    val name = Regex("(?im)^FN:(.*)$").find(text)?.groupValues?.get(1)?.trim()
    val phones = Regex("(?im)^[^:\\n]*TEL[^:\\n]*:(.*)$").findAll(text)
        .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
    return buildList {
        name?.takeIf { it.isNotBlank() }?.let(::add)
        addAll(phones)
    }.ifEmpty { listOf("Контакт") }
}

class VCardCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "contact"
    override val meta = CapabilityMeta(priority = 11)
    override fun label(state: ObjectState) = "Добавить в контакты"
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_VCARD)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("vcard") }
}

class VCardRealizer @Inject constructor(
    private val viewer: Viewer,
) : Realizer {
    override val capabilityId = VCardCapability.ID

    override suspend fun preview(input: PointObject): Preview {
        val text = withContext(Dispatchers.IO) {
            File(input.uri.value).takeIf { it.isFile }?.readText().orEmpty()
        }
        return Preview("Добавить в контакты", vCardSummary(text), confirmLabel = "Добавить")
    }

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

                viewer.view(input.copy(mime = VCARD_MIME))
                ActionResult.Done("Открываю контакт…")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть контакт", recoverable = true) }
        }

    private companion object { const val VCARD_MIME = "text/x-vcard" }
}
