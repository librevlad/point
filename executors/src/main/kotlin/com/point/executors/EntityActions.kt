package com.point.executors

import com.point.core.flow.CalendarInserter
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.Realizer
import com.point.core.flow.UrlOpener
import java.net.URLEncoder
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.isFileBacked
import com.point.core.flow.asExtractedKind
import com.point.core.model.Preview
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
/** The text entities live in: a TEXT object's own content, or — for an IMAGE the OCR enricher
 *  already read (#64) — its recognised-text sidecar, so entity actions work on screenshots. */
internal fun entitySourceText(input: PointObject): String {
    val sidecar = input.metadata[META_OCR_TEXT_REF]
        ?.let { path -> runCatching { File(path).takeIf(File::isFile)?.readText() }.getOrNull() }
    if (sidecar != null) return sidecar
    // An extracted object (#222) has no file: its value IS its text. Without this every action
    // on a found thing looked for `File("вул. Сонячна, 15")`, found nothing, and failed (#237).
    if (!input.state.kind.isFileBacked) return input.uri.value
    return File(input.uri.value).takeIf { it.isFile }?.readText().orEmpty()
}

internal suspend fun firstEntity(extractor: EntityExtractor, input: PointObject, type: EntityType): String? {
    // The object may BE that entity. An `Address` knows its own value; re-running a model over
    // fifteen characters to rediscover it is slower, less reliable, and can only disagree.
    if (type.asExtractedKind() == input.state.kind) return input.uri.value.takeIf { it.isNotBlank() }
    val text = entitySourceText(input)
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

// --- Открыть на карте ---
class MapCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "map"
    override val meta = CapabilityMeta(priority = 15)
    override fun label(state: ObjectState) = "Открыть на карте"
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_ADDRESS)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("map") }
}

class MapRealizer @Inject constructor(
    private val extractor: EntityExtractor,
    private val opener: UrlOpener,
) : Realizer {
    override val capabilityId = MapCapability.ID

    override suspend fun preview(input: PointObject): Preview? =
        firstEntity(extractor, input, EntityType.ADDRESS)
            ?.let { Preview("Открыть на карте", listOf(it), confirmLabel = "Открыть") }

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = firstEntity(extractor, input, EntityType.ADDRESS) ?: error("Адрес не найден")
                opener.open("geo:0,0?q=" + URLEncoder.encode(address, "UTF-8"))
                ActionResult.Done("Открываю на карте: $address")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть карту", recoverable = true) }
        }
}

// --- Создать событие ---
class EventCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "event"
    override val meta = CapabilityMeta(priority = 16)
    override fun label(state: ObjectState) = "Создать событие"
    // IS_MEETING (#89): a recognised meeting deserves the bubble even when the date
    // parser found nothing — the calendar sheet lets the user pick the time.
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_DATE) || state.has(Feature.IS_MEETING)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("event") }
}

class EventRealizer @Inject constructor(
    private val calendar: CalendarInserter,
) : Realizer {
    override val capabilityId = EventCapability.ID

    override suspend fun preview(input: PointObject): Preview = withContext(Dispatchers.IO) {
        Preview("Создать событие", listOf(eventTitle(input)), confirmLabel = "Создать")
    }

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                calendar.insertEvent(eventTitle(input))
                ActionResult.Done("Создаю событие")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось создать событие", recoverable = true) }
        }

    /** The first non-blank line is the most sensible event title; the calendar screen lets the user
     *  set the time (ML Kit gives us that a date exists, not yet a parsed timestamp). */
    private fun eventTitle(input: PointObject): String =
        entitySourceText(input).lineSequence().map { it.trim() }
            .firstOrNull { it.isNotBlank() }?.take(100) ?: "Событие"
}
