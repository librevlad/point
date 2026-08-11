package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.GraphState
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.Realizer
import com.point.core.flow.applyFixes
import com.point.core.flow.fixPrompt
import com.point.core.flow.fixableFacts
import com.point.core.flow.fixedMessage
import com.point.core.flow.hasFixableFacts
import com.point.core.flow.labelNeedingKey
import com.point.core.flow.parseFixes
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Findings
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * «Исправить ошибки» (#666): всё знание объекта уходит в модель, она возвращает исправления
 * опечаток распознавания. Знание остаётся знанием того же объекта — нового объекта не рождается.
 *
 * Дверь появляется только там, где есть что исправлять: на объекте без знания её нет вовсе
 * (решение владельца). Поэтому применимость смотрит на Graph State, а не на форму объекта.
 */
class FixErrorsCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "ai"

    override val meta = CapabilityMeta(priority = 32, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)

    override fun label(state: ObjectState) = labelNeedingKey("Исправить ошибки", keys.keySet())

    // Знание не привязано к форме: исправлять бывает что у любого объекта. Сужает не форма,
    // а само знание — им и занимается разбор по Graph State ниже.
    override fun accepts(state: ObjectState) = true

    override fun accepts(graph: GraphState) = hasFixableFacts(graph.facts)

    override fun produces(state: ObjectState) = state

    override fun yields(state: ObjectState) = ActionYield.Same()
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object { val ID = CapabilityId("fix-errors") }
}

/**
 * «Исправить сильнее»: в модель уходит и сам снимок — знание сверяется с источником.
 * Подпись честности обязательна: объект покидает устройство (Конституция §11).
 */
class FixErrorsStrongerCapability @Inject constructor(
    private val keys: AiReadiness,
) : Capability {
    override val id = ID
    override val icon = "ai"

    override val meta = CapabilityMeta(priority = 33, cost = Cost.PAID, latency = Latency.SLOW, network = true, auth = true)

    override fun label(state: ObjectState) = labelNeedingKey("Исправить сильнее", keys.keySet())

    // Сверять с источником есть смысл там, где источник — снимок: у текста модель уже
    // видела всё, что можно, на первой ступени.
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE

    override fun accepts(graph: GraphState) =
        accepts(graph.state) && hasFixableFacts(graph.facts)

    override fun produces(state: ObjectState) = state

    // Знание того же объекта, как и у первой ступени. Что снимок уйдёт наружу, человек
    // узнаёт запросом согласия перед выполнением (ADR-0001 §19) — там названо и куда именно.
    override fun yields(state: ObjectState) = ActionYield.Same()
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object { val ID = CapabilityId("fix-errors-stronger") }
}

class FixErrorsRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = FixErrorsCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        fix(llm, input, withObject = false)
}

class FixErrorsStrongerRealizer @Inject constructor(
    private val llm: LlmClient,
) : Realizer {
    override val capabilityId = FixErrorsStrongerCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        fix(llm, input, withObject = true)
}

internal suspend fun fix(llm: LlmClient, input: PointObject, withObject: Boolean): ActionResult =
    withContext(Dispatchers.IO) {
        val facts = fixableFacts(input.metadata)
        if (facts.isEmpty()) {
            return@withContext ActionResult.Failure("Исправлять нечего — знания об объекте пока нет", recoverable = false)
        }
        reportStage(if (withObject) "Сверяю со снимком" else "Проверяю найденное")
        runCatching {

            // Первой ступени снимок не нужен: она правит опечатки в самом знании, и
            // отправлять наружу больше, чем требуется, незачем (Конституция §11).
            val asked = if (withObject) input else textOnly(input)
            val answer = File(llm.run(asked, fixPrompt(facts, withObject)).uri.value).readText()
            val fixes = parseFixes(answer, facts)

            // Знание об объекте, а не новый объект (ADR-0001 §18): человек остаётся на месте.
            ActionResult.Done(
                fixedMessage(fixes.size),
                Findings(metadata = applyFixes(input.metadata, fixes)).takeIf { fixes.isNotEmpty() },
            )
        }.getOrElse {
            ActionResult.Failure(it.message ?: "Не удалось проверить знание", recoverable = true)
        }
    }

/** Ссылка на слой OCR — не текст объекта: на первой ступени наружу уходит только знание. */
private fun textOnly(input: PointObject) =
    if (input.state.kind == ObjectKind.TEXT) input
    else input.copy(mime = "text/plain", metadata = input.metadata - META_OCR_TEXT_REF)
