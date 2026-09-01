package com.point.executors

import com.point.core.flow.CalendarInserter
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
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
import com.point.core.flow.asMetaKey
import com.point.core.model.Preview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private fun dialable(phone: String) = phone.filter { it.isDigit() || it == '+' }

class CallCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "call"

    // #466: «я всё равно на незнакомый номер звонить не буду». Звонок больше не стоит высоко
    // сам по себе — порядок решает то, чем человек пользуется (обучение на выборах).
    // Шаг кончается набором номера в чужом приложении (#1131).
    override val meta = CapabilityMeta(priority = 40, handsOff = true)
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
                val phone = com.point.core.flow.firstEntity(extractor, input, EntityType.PHONE) ?: error("Номер не найден")
                opener.open("tel:" + dialable(phone))
                ActionResult.Done("Открыл набор: $phone")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось позвонить", recoverable = true) }
        }
}

class SmsCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "message"
    override val meta = CapabilityMeta(priority = 42, handsOff = true)
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
                val phone = com.point.core.flow.firstEntity(extractor, input, EntityType.PHONE) ?: error("Номер не найден")
                opener.open("smsto:" + dialable(phone))
                ActionResult.Done("Открыл сообщение: $phone")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть сообщения", recoverable = true) }
        }
}

class EmailCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "email"
    override val meta = CapabilityMeta(priority = 13, handsOff = true)
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
                val email = com.point.core.flow.firstEntity(extractor, input, EntityType.EMAIL) ?: error("Email не найден")
                opener.open("mailto:$email")
                ActionResult.Done("Открыл письмо: $email")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть почту", recoverable = true) }
        }
}

class MapCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "map"
    override val meta = CapabilityMeta(priority = 15, handsOff = true)
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
        com.point.core.flow.firstEntity(extractor, input, EntityType.ADDRESS)
            ?.let { Preview("Открыть на карте", listOf(it), confirmLabel = "Открыть") }

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = com.point.core.flow.firstEntity(extractor, input, EntityType.ADDRESS) ?: error("Адрес не найден")
                opener.open("geo:0,0?q=" + URLEncoder.encode(address, "UTF-8"))
                ActionResult.Done("Открыл на карте: $address")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть карту", recoverable = true) }
        }
}

class EventCapability(
    private val today: () -> java.time.LocalDate,
) : Capability {

    @Inject constructor() : this({ java.time.LocalDate.now() })

    override val id = ID
    override val icon = "event"
    override val meta = CapabilityMeta(priority = 16, handsOff = true)
    override fun label(state: ObjectState) = "Создать событие"

    override fun accepts(state: ObjectState) = state.has(Feature.HAS_DATE) || state.has(Feature.IS_MEETING)

    // «Дата в прошлом не может создавать событие» (#651): дата остаётся знанием,
    // но дверь события открывает только дата сегодня и позже — или сама встреча.
    override fun accepts(graph: com.point.core.flow.GraphState) =
        graph.state.has(Feature.IS_MEETING) ||
            (
                graph.state.has(Feature.HAS_DATE) &&
                    com.point.core.flow.hasUpcomingDate(graph.obj.metadata, today())
                )

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

                // Действию отдаётся то, что Point уже знает (#1035, #1138): найденный день
                // лежит в графе — он же открывает дверь события, — и в календарь едет он,
                // а не сегодняшнее число.
                calendar.insertEvent(eventTitle(input), eventDay(input))

                // Исход шага — то, что сделал Point, а не то, что случится в чужом
                // приложении (#1131). «Создаю событие» звучало продолжающейся работой и
                // висело вечно: человек уходил из календаря ни с чем, а Point всё «создавал».
                // Событие создаёт человек в календаре, и знать об этом Point не может.
                ActionResult.Done("Открыл календарь — событие создаётся там")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть календарь", recoverable = true) }
        }

    /**
     * День события — из знания объекта, ближайший к сегодня из найденных.
     *
     * Дата в прошлом дверь события не открывает (#651), поэтому берётся первый день, который
     * ещё впереди; если такого нет — день не называется вовсе, и календарь решит сам.
     */
    private fun eventDay(input: PointObject): java.time.LocalDate? {
        val today = java.time.LocalDate.now()
        val hint = com.point.core.flow.dayOrderOf(com.point.core.flow.entitySourceText(input))
        return com.point.core.flow.datesKnown(input.metadata)
            .mapNotNull { com.point.core.flow.humanDayOf(it, hint) }
            .filterNot { it.isBefore(today) }
            .minOrNull()
    }

    private fun eventTitle(input: PointObject): String =
        com.point.core.flow.entitySourceText(input).lineSequence().map { it.trim() }
            .firstOrNull { it.isNotBlank() }?.take(100) ?: "Событие"
}
