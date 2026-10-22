package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview
import com.point.core.model.tr
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Письмо, карта и событие — одни на телефон и компьютер (#1379). Что открыть, решает Point по
 * знанию объекта; чем открыть — орган устройства за [UrlOpener] и [CalendarInserter]: у
 * телефона приложения Android, у компьютера почта, браузер и календарь Windows.
 */

class EmailCapability : Capability {
    override val id = ID
    override val icon = "email"
    override val meta = CapabilityMeta(priority = 13, handsOff = true)
    override fun label(state: ObjectState) = tr("Написать письмо")
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_EMAIL)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("email") }
}

class EmailRealizer(
    private val extractor: EntityExtractor,
    private val opener: UrlOpener,
) : Realizer {
    override val capabilityId = EmailCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val email = firstEntity(extractor, input, EntityType.EMAIL) ?: error(tr("Email не найден"))
                opener.open("mailto:$email")
                ActionResult.Done(tr("Открыл письмо: {0}", email))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось открыть почту"), recoverable = true) }
        }
}

class MapCapability : Capability {
    override val id = ID
    override val icon = "map"
    override val meta = CapabilityMeta(priority = 15, handsOff = true)
    override fun label(state: ObjectState) = tr("Открыть на карте")
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_ADDRESS)
    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("map") }
}

class MapRealizer(
    private val extractor: EntityExtractor,
    private val opener: UrlOpener,
) : Realizer {
    override val capabilityId = MapCapability.ID

    override suspend fun preview(input: PointObject): Preview? =
        firstEntity(extractor, input, EntityType.ADDRESS)
            ?.let { Preview(tr("Открыть на карте"), listOf(it), confirmLabel = tr("Открыть")) }

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = firstEntity(extractor, input, EntityType.ADDRESS) ?: error(tr("Адрес не найден"))
                opener.open("geo:0,0?q=" + URLEncoder.encode(address, "UTF-8"))
                ActionResult.Done(tr("Открыл на карте: {0}", address))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось открыть карту"), recoverable = true) }
        }
}

class EventCapability(
    private val today: () -> java.time.LocalDate,
) : Capability {

    constructor() : this({ java.time.LocalDate.now() })

    override val id = ID
    override val icon = "event"
    override val meta = CapabilityMeta(priority = 16, handsOff = true)
    override fun label(state: ObjectState) = tr("Создать событие")

    override fun accepts(state: ObjectState) = state.has(Feature.HAS_DATE) || state.has(Feature.IS_MEETING)

    // «Дата в прошлом не может создавать событие» (#651): дата остаётся знанием,
    // но дверь события открывает только дата сегодня и позже — или сама встреча.
    override fun accepts(graph: GraphState) =
        graph.state.has(Feature.IS_MEETING) ||
            (
                graph.state.has(Feature.HAS_DATE) &&
                    hasUpcomingDate(graph.obj.metadata, today())
                )

    override fun produces(state: ObjectState) = state
    override fun intents(state: ObjectState) = setOf(Intent.OPEN)

    companion object { val ID = CapabilityId("event") }
}

class EventRealizer(
    private val calendar: CalendarInserter,
) : Realizer {
    override val capabilityId = EventCapability.ID

    override suspend fun preview(input: PointObject): Preview = withContext(Dispatchers.IO) {
        Preview(tr("Создать событие"), listOf(eventTitle(input)), confirmLabel = tr("Создать"))
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
                ActionResult.Done(tr("Открыл календарь — событие создаётся там"))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось открыть календарь"), recoverable = true) }
        }

    /**
     * День события — из знания объекта, ближайший к сегодня из найденных (#651). Тот же
     * [eventDays], что открывает дверь события, чтобы продиктованная
     * «11 сентября в 15:00» и в календарь поехала днём, а не сегодняшним числом (#1426 ч.2).
     * Если ближайшего дня нет — день не называется, и календарь решит сам.
     */
    private fun eventDay(input: PointObject): java.time.LocalDate? =
        eventDays(input.metadata, java.time.LocalDate.now()).minOrNull()

    private fun eventTitle(input: PointObject): String =
        entitySourceText(input).lineSequence().map { it.trim() }
            .firstOrNull { it.isNotBlank() }?.take(100) ?: tr("Событие")
}
