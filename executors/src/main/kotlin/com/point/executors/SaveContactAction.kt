package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ContactInserter
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SaveContactCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "contact"

    override val meta = CapabilityMeta(priority = 17)
    override fun label(state: ObjectState) = "Сохранить контакт"
    override fun accepts(state: ObjectState) =
        !state.has(Feature.HAS_VCARD) && (state.has(Feature.HAS_PHONE) || state.has(Feature.HAS_EMAIL))
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("save-contact") }
}

class SaveContactRealizer @Inject constructor(
    private val extractor: EntityExtractor,
    private val inserter: ContactInserter,
) : Realizer {
    override val capabilityId = SaveContactCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val phone = contactValue(input, "phone", EntityType.PHONE)
                val email = contactValue(input, "email", EntityType.EMAIL)

                val shown = phone ?: email ?: error("Ни телефона, ни почты не нашлось")
                inserter.insertContact(phone, email)
                ActionResult.Done("Сохраняю контакт: $shown")
            }.getOrElse {
                ActionResult.Failure(it.message ?: "Не удалось сохранить контакт", recoverable = true)
            }
        }

    private suspend fun contactValue(input: PointObject, key: String, type: EntityType): String? =
        input.metadata[META_ENTITY_PREFIX + key]?.takeIf { it.isNotBlank() }
            ?: firstEntity(extractor, input, type)
}
