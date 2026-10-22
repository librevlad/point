package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.GraphState
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Realizer
import com.point.core.flow.UrlOpener
import com.point.core.model.tr
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

private fun dialable(phone: String) = phone.filter { it.isDigit() || it == '+' }

class CallCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "call"

    // #466: «я всё равно на незнакомый номер звонить не буду». Звонок больше не стоит высоко
    // сам по себе — порядок решает то, чем человек пользуется (обучение на выборах).
    // Шаг кончается набором номера в чужом приложении (#1131).
    override val meta = CapabilityMeta(priority = 40, handsOff = true)
    override fun label(state: ObjectState) = tr("Позвонить")
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
                val phone = com.point.core.flow.firstEntity(extractor, input, EntityType.PHONE) ?: error(tr("Номер не найден"))
                opener.open("tel:" + dialable(phone))
                ActionResult.Done(tr("Открыл набор: {0}", phone))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось позвонить"), recoverable = true) }
        }
}

class SmsCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "message"
    override val meta = CapabilityMeta(priority = 42, handsOff = true)
    override fun label(state: ObjectState) = tr("Сообщение")
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
                val phone = com.point.core.flow.firstEntity(extractor, input, EntityType.PHONE) ?: error(tr("Номер не найден"))
                opener.open("smsto:" + dialable(phone))
                ActionResult.Done(tr("Открыл сообщение: {0}", phone))
            }.getOrElse { ActionResult.Failure(it.message ?: tr("Не удалось открыть сообщения"), recoverable = true) }
        }
}

/*
 * «Написать письмо», «Открыть на карте» и «Создать событие» живут в `:core:flow` — одни на
 * телефон и компьютер (#1379). Здесь только двери Hilt; исполнители — `@Provides` в
 * `CapabilityModule`. Члены переписаны руками, а не `by`-делегатом: kotlin-делегирование в
 * `@Inject`-классе роняет KSP всего модуля.
 */

class EmailCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.EmailCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}

class MapCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.MapCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}

class EventCapabilityOnPhone @Inject constructor() : Capability {
    private val inner = com.point.core.flow.EventCapability()
    override val id get() = inner.id
    override val icon get() = inner.icon
    override val meta get() = inner.meta
    override fun label(state: ObjectState): String = inner.label(state)
    override fun label(graph: GraphState): String = inner.label(graph)
    override fun accepts(state: ObjectState): Boolean = inner.accepts(state)
    override fun accepts(graph: GraphState): Boolean = inner.accepts(graph)
    override fun produces(state: ObjectState): ObjectState? = inner.produces(state)
    override fun yields(state: ObjectState): ActionYield = inner.yields(state)
    override fun intents(state: ObjectState): Set<Intent> = inner.intents(state)
    override fun missing(state: ObjectState): String? = inner.missing(state)
}
