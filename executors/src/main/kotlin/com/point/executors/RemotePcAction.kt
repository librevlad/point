package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PC_DEVICE_REVOKED
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.pcUnreachableText
import com.point.core.flow.Realizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject

/**
 * A PC-advertised action as a phone bubble (#80): «Открыть на компьютере» sits beside
 * the local actions, indistinguishable in the UI — the realizer ships the object over
 * the one channel there is with the action id, and the PC runs it. Synthesised one
 * pair per cached advertisement, exactly like remembered app picks (#66).
 *
 * #316: компьютер умеет объявить действие недоступным с причиной («нет принтера»). Такое
 * действие не становится кнопкой — оно уходит в «Почти доступно» (#97) той же причиной,
 * без обещаний: раньше принтера не было → действие не объявлялось вовсе → человек читал это
 * как «Point не умеет печатать», хотя умеет — печатать некуда именно сейчас.
 */
class RemotePcCapability(
    private val action: PcRemoteAction,
    private val links: PcLinks,
) : Capability {
    override val id = idFor(action)
    override val icon = "pc"
    // Между своими устройствами, запечатанно: network=false НАМЕРЕННО (та же причина, что у
    // «На компьютер»).
    override val meta = CapabilityMeta(priority = 76, latency = Latency.FAST)
    override fun label(state: ObjectState) = action.label
    override fun accepts(state: ObjectState) =
        action.unavailable == null && fitsThisObject(state)

    override fun produces(state: ObjectState) = state // terminal — the action happens on the PC

    /** Причина показывается только там, где кнопка и была бы: объект подходящего вида и
     *  компьютер на связи. Иначе «нет принтера» всплывёт рядом с объектом, который на ПК
     *  вообще не поедет, — шум вместо объяснения. Причины нет → и подсказки нет (молчание
     *  честнее выдуманного текста). */
    override fun missing(state: ObjectState): String? =
        action.unavailable?.takeIf { it.isNotBlank() && fitsThisObject(state) }

    private fun fitsThisObject(state: ObjectState) =
        state.kind.isFileBacked &&
            (action.kinds.isEmpty() || state.kind.name in action.kinds) &&
            links.current() != null

    companion object {
        fun idFor(action: PcRemoteAction) = CapabilityId("pc-do:${action.id}")
    }
}

class RemotePcRealizer(
    private val action: PcRemoteAction,
    private val links: PcLinks,
    private val transport: PcTransport,
) : Realizer {
    override val capabilityId = RemotePcCapability.idFor(action)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        // #316: недоступное не отправляется никогда — даже если до реализатора добрались в
        // обход экрана (сохранённая цепочка, устаревший кэш действий ПК). Объект остаётся
        // на телефоне, человек читает ту же причину, что и в «Почти доступно».
        action.unavailable?.let { why ->
            val reason = "Компьютер сейчас не может это сделать" + if (why.isBlank()) "" else " — $why"
            return ActionResult.Failure(reason, recoverable = true)
        }
        val pc = links.current()
            ?: return ActionResult.Failure(
                pcUnreachableText(com.point.core.flow.PcUnreachable.NOT_IN_CIRCLE),
                recoverable = true,
            )
        val name = input.metadata["name"] ?: "объект"
        // Те же слова, что у «На компьютер» (#288): работа буквально одна — [PC_SEND_STAGE].
        reportStage(PC_SEND_STAGE)
        return when (val outcome = transport.send(pc, input, name, input.metadata, action.id)) {
            // #114: «готово» имеет право сказать только тот, кто это сделал. Доставка файла —
            // не выполнение действия: пока компьютер не назвал исход, телефон говорит ровно то,
            // что знает сам, — теми же словами, что и соседнее «На компьютер».
            is PcSendOutcome.Sent -> when (val done = outcome.action) {
                null -> ActionResult.Done("Отправлено на компьютер")
                is PcActionOutcome.Done ->
                    // Слова компьютера сильнее наших: «В очереди «HP» · проверьте принтер» честнее
                    // общего «готово», потому что сказано тем, кто печатал.
                    ActionResult.Done(done.detail?.takeIf { it.isNotBlank() } ?: "${action.label} — готово")
                is PcActionOutcome.Failed -> ActionResult.Failure(
                    done.reason.takeIf { it.isNotBlank() }
                        ?: "Компьютер не смог выполнить «${action.label}»",
                    recoverable = true,
                )
            }
            // Соседние отказы об одном и том же событии обязаны звучать одинаково (#524): пока
            // у каждого действия были свои слова, шесть формулировок описывали три события.
            PcSendOutcome.Rejected -> ActionResult.Failure(PC_DEVICE_REVOKED, recoverable = true)
            is PcSendOutcome.Unreachable ->
                ActionResult.Failure(pcUnreachableText(outcome.why), recoverable = true)
        }
    }
}
