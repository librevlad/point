package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.CurrentKnowledge
import com.point.core.flow.FIX_TEXT_NOT_APPLIED
import com.point.core.flow.GraphState
import com.point.core.flow.Latency
import com.point.core.flow.LlmClient
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.flow.applyFixes
import com.point.core.flow.fixPrompt
import com.point.core.flow.fixText
import com.point.core.flow.fixTextPrompt
import com.point.core.flow.fixTextWindow
import com.point.core.flow.fixableFacts
import com.point.core.flow.fixedMessage
import com.point.core.flow.fixedTextMessage
import com.point.core.flow.fixesForFacts
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
 *
 * У текстового объекта знание — сам текст (#1023): его и проверяет первая ступень, а
 * исправленный текст ложится знанием того же объекта. Значения, вычитанные из этого текста,
 * следуют за его правкой: вырезанный из прочитанного снимка фрагмент несёт ошибки чтения и в
 * тексте, и в значениях, и второго пути починки у них нет — «Исправить сильнее» текст не берёт.
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

    override fun accepts(graph: GraphState) = ownsText(graph.state) || hasFixableFacts(graph.facts)

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
    private val known: CurrentKnowledge,
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = FixErrorsCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        if (ownsText(input.state)) fixOwnText(llm, known, store, input) else fix(llm, input, withObject = false)
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
            // отправлять наружу больше, чем требуется, незачем (принцип #1244).
            val asked = if (withObject) input else textStandIn(input)
            val answer = File(llm.run(asked, fixPrompt(facts, withObject)).uri.value).readText()
            // Гейт формы у обоих путей один (#666, #1032): исправленное судится по той же
            // странице, что и найденное впервые, — иначе «Понять» брала 13-значную накладную
            // по слову рядом, а «Исправить ошибки» ту же накладную молча отказывалась править.
            val fixes = parseFixes(answer, facts, entitySourceText(input))

            // Знание об объекте, а не новый объект (ADR-0001 §18): человек остаётся на месте.
            ActionResult.Done(
                fixedMessage(fixes.size),
                Findings(metadata = applyFixes(input.metadata, fixes)).takeIf { fixes.isNotEmpty() },
            )
        }.getOrElse {
            ActionResult.Failure(it.message ?: "Не удалось проверить знание", recoverable = true)
        }
    }

/** Текстовый объект: его текст и есть знание, а не прочтение чего-то другого (#1023). */
internal fun ownsText(state: ObjectState) = state.kind == ObjectKind.TEXT

/**
 * Первая ступень над текстовым объектом (#1023): в модель уходит сам текст, правки ложатся
 * поверх него, исправленный текст становится прочтением того же объекта — тем же ключом,
 * каким лежит любое чтение (#1097), поэтому экран и следующие действия видят уже его.
 * Исходные байты объекта не трогаются, а что именно изменилось — названо в итоге действия.
 * Значения, вычитанные из этого текста, следуют за правкой теми же парами (`fixesForFacts`).
 */
private suspend fun fixOwnText(
    llm: LlmClient,
    known: CurrentKnowledge,
    store: ObjectStore,
    input: PointObject,
): ActionResult = withContext(Dispatchers.IO) {

    // Текст берётся целиком: правка ложится поверх всего текста, и обрезанное прочтение
    // потеряло бы хвост. В запрос же уходит только окно — длинный текст одним вопросом не
    // проверить, а итог честно говорит, какая часть проверена.
    val text = known.textOf(input, limit = Int.MAX_VALUE)?.takeIf { it.isNotBlank() }
        ?: return@withContext ActionResult.Failure("Исправлять нечего — текста у объекта нет", recoverable = false)
    val window = fixTextWindow(text)
    reportStage("Проверяю текст")
    runCatching {
        val answer = File(llm.run(input, fixTextPrompt(window)).uri.value).readText()
        val fixed = fixText(text, answer)
        when {
            fixed.fixes.isNotEmpty() -> {
                val ref = store.newScratchFile("txt")
                File(ref.value).writeText(fixed.text)

                // Знание об объекте, а не новый объект (ADR-0001 §18): человек остаётся на месте.
                ActionResult.Done(
                    fixedTextMessage(fixed, checked = window.length, total = text.length),
                    Findings(
                        metadata = mapOf(META_OCR_TEXT_REF to ref.value) +
                            applyFixes(input.metadata, fixesForFacts(fixableFacts(input.metadata), fixed.fixes)),
                    ),
                )
            }

            // Правки были, но ни одна не легла — срыв операции, а не знание «ошибок нет»
            // (Конституция §13): «не нашлось» говорится только там, где не нашлось.
            fixed.missed.isNotEmpty() -> ActionResult.Failure(FIX_TEXT_NOT_APPLIED, recoverable = true)

            else -> ActionResult.Done(fixedTextMessage(fixed, checked = window.length, total = text.length))
        }
    }.getOrElse {
        ActionResult.Failure(it.message ?: "Не удалось проверить текст", recoverable = true)
    }
}
