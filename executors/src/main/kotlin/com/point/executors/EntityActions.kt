package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Realizer
import com.point.core.flow.UrlOpener
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Actions on entities detected in text — Point as a "right-click": text with a number offers
 * «Позвонить»/«Сообщение», text with an address of mail offers «Написать письмо». Each capability
 * gates on the enriched [Feature]; each realizer re-extracts the value on-device via
 * [EntityExtractor] and opens the matching app through [UrlOpener] (ACTION_VIEW on a scheme URI —
 * `tel:` / `smsto:` / `mailto:`). Terminal (`produces == state`, returns `Done`).
 */
internal suspend fun firstEntity(extractor: EntityExtractor, input: PointObject, type: EntityType): String? {
    val text = File(input.uri.value).takeIf { it.isFile }?.readText().orEmpty()
    if (text.isBlank()) return null
    return extractor.extract(text).firstOrNull { it.type == type }?.value
}

private fun dialable(phone: String) = phone.filter { it.isDigit() || it == '+' }

// --- Позвонить ---
class CallCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "call"
    override val meta = CapabilityMeta(priority = 12)
    override fun label(state: ObjectState) = "Позвонить"
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_PHONE)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("call") }
}

class CallRealizer @Inject constructor(
    private val extractor: EntityExtractor,
    private val opener: UrlOpener,
) : Realizer {
    override val capabilityId = CallCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val phone = firstEntity(extractor, input, EntityType.PHONE) ?: error("Номер не найден")
                opener.open("tel:" + dialable(phone))
                ActionResult.Done("Звоню: $phone")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось позвонить", recoverable = true) }
        }
}

// --- Сообщение ---
class SmsCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "message"
    override val meta = CapabilityMeta(priority = 14)
    override fun label(state: ObjectState) = "Сообщение"
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_PHONE)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("sms") }
}

class SmsRealizer @Inject constructor(
    private val extractor: EntityExtractor,
    private val opener: UrlOpener,
) : Realizer {
    override val capabilityId = SmsCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val phone = firstEntity(extractor, input, EntityType.PHONE) ?: error("Номер не найден")
                opener.open("smsto:" + dialable(phone))
                ActionResult.Done("Сообщение: $phone")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть сообщения", recoverable = true) }
        }
}

// --- Написать письмо ---
class EmailCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "email"
    override val meta = CapabilityMeta(priority = 13)
    override fun label(state: ObjectState) = "Написать письмо"
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_EMAIL)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("email") }
}

class EmailRealizer @Inject constructor(
    private val extractor: EntityExtractor,
    private val opener: UrlOpener,
) : Realizer {
    override val capabilityId = EmailCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val email = firstEntity(extractor, input, EntityType.EMAIL) ?: error("Email не найден")
                opener.open("mailto:$email")
                ActionResult.Done("Письмо: $email")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть почту", recoverable = true) }
        }
}
