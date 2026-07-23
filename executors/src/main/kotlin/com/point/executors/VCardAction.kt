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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * A shared contact card (`.vcf`) → the system Contacts app's import screen. Lit up once
 * [com.point.data.VCardEnricher] flags [Feature.HAS_VCARD]: the right action for a contact is
 * "add it", not "read the raw vCard". Ranks ahead of the entity actions (Call/Sms that ML Kit
 * also finds in the card's phone number), so "add to contacts" leads. Terminal — hands the card
 * to Contacts via ACTION_VIEW.
 */
class VCardCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "contact"
    override val meta = CapabilityMeta(priority = 11)
    override fun label(state: ObjectState) = "Добавить в контакты"
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_VCARD)
    override fun produces(state: ObjectState) = state // terminal
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("vcard") }
}

class VCardRealizer @Inject constructor(
    private val viewer: Viewer,
) : Realizer {
    override val capabilityId = VCardCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // Force the vCard MIME so Contacts is the handler even when the share arrived with a
                // generic type (octet-stream / text/plain) but vCard content.
                viewer.view(input.copy(mime = VCARD_MIME))
                ActionResult.Done("Открываю контакт…")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть контакт", recoverable = true) }
        }

    private companion object { const val VCARD_MIME = "text/x-vcard" }
}
