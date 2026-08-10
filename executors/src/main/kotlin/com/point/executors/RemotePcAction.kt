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

class RemotePcCapability(
    private val action: PcRemoteAction,
    private val links: PcLinks,
) : Capability {
    override val id = idFor(action)
    override val icon = "pc"

    override val meta = CapabilityMeta(priority = 76, latency = Latency.FAST, localOnly = true)
    override fun label(state: ObjectState) = action.label
    override fun accepts(state: ObjectState) =
        action.unavailable == null && fitsThisObject(state)

    override fun produces(state: ObjectState) = state

    override fun missing(state: ObjectState): String? =
        action.unavailable?.takeIf { it.isNotBlank() && fitsThisObject(state) }

    private fun fitsThisObject(state: ObjectState) =

        state.kind != ObjectKind.COLLECTION &&
            (action.kinds.isEmpty() || state.kind.name in action.kinds) &&
            links.current() != null

    companion object {

        fun idFor(action: PcRemoteAction): CapabilityId {
            val shared = CapabilityId(action.id)
            return if (shared in com.point.core.flow.capabilities.sharedCapabilityIds) {
                shared
            } else {
                CapabilityId("pc-do:${action.id}")
            }
        }
    }
}

class RemotePcRealizer(
    private val action: PcRemoteAction,
    private val links: PcLinks,
    private val transport: PcTransport,

    private val store: com.point.core.flow.ObjectStore? = null,
    private val classifier: com.point.core.flow.ObjectClassifier? = null,
) : Realizer {
    override val capabilityId = RemotePcCapability.idFor(action)

    override val meta = com.point.core.flow.RealizerMeta(
        kind = if (action.leavesCircle) com.point.core.flow.RealizerKind.CLOUD else com.point.core.flow.RealizerKind.LOCAL,
    )

    /**
     * ADR-0001 §20, вариант A (Этап 9): знание с компьютера возвращается ЗНАНИЕМ исходника
     * через `Done.findings`, а произведённый файл — новым объектом Graph. Автоперехода нет:
     * человек остаётся на исходном объекте и видит результат как найденное.
     */
    private suspend fun materialize(outcome: PcSendOutcome.Sent, input: PointObject): ActionResult? {
        val returned = outcome.returned ?: return null
        val place = store ?: return null
        return runCatching {
            val ref = place.newScratchFile(returned.name.substringAfterLast('.', "bin"))
            val bytes = returned.bytes
            java.io.File(ref.value).writeBytes(bytes)

            // Результат с компьютера приезжал объектом вида UNKNOWN — телефон рисовал
            // «Объект» со знаком вопроса вместо «Перевод · Текст», хотя вид известен
            // по mime и байтам не хуже, чем у любого другого объекта (#681).
            val state = classifier?.classify(returned.mime, bytes.size.toLong(), returned.name, bytes.take(64).toByteArray())
                ?: com.point.core.model.ObjectState(com.point.core.model.ObjectKind.UNKNOWN)
            val produced = PointObject(
                id = resultId(input.id, returned.name),
                mime = returned.mime,
                uri = ref,
                state = state,
                metadata = mapOf("name" to returned.name),
                sourceObjects = listOf(input.id),
                creatorAction = capabilityId.value,
            )
            ActionResult.Done(
                "${action.label} — готово: ${returned.name}",
                com.point.core.model.Findings(
                    metadata = returned.understanding,
                    objects = listOf(produced),
                    relations = listOf(
                        com.point.core.model.Relation(
                            produced.id,
                            com.point.core.model.RelationType.FOUND_IN,
                            input.id,
                        ),
                    ),
                ),
            )
        }.getOrNull()
    }

    private fun resultId(sourceId: String, name: String): String =
        sourceId + ":pc:" + action.id + ":" + name.filter(Char::isLetterOrDigit).lowercase()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {

        action.unavailable?.let { why ->
            val reason = "Компьютер сейчас не может это сделать" + if (why.isBlank()) "" else " — $why"
            return ActionResult.Failure(reason, recoverable = true)
        }
        val pc = links.current()
            ?: return ActionResult.Failure(
                pcUnreachableText(com.point.core.flow.PcUnreachable.NOT_IN_CIRCLE),
                recoverable = true,
            )
        val name = humanSendName(input)

        reportStage(PC_SEND_STAGE)
        return when (val outcome = transport.send(pc, input, name, input.metadata, action.id)) {

            is PcSendOutcome.Sent -> materialize(outcome, input) ?: when (val done = outcome.action) {

                // «Назвать место словами» (#646): человек знает, где искать файл (PC3).
                null -> ActionResult.Done(PC_SENT_WHERE)
                is PcActionOutcome.Done ->

                    ActionResult.Done(
                        done.detail?.takeIf { it.isNotBlank() } ?: "${action.label} — готово",
                        // Понятое компьютером ложится в исходник тем же путём Done+findings:
                        // перенос не теряет знание (PC2).
                        com.point.core.model.Findings(metadata = outcome.understanding)
                            .takeIf { outcome.understanding.isNotEmpty() },
                    )
                is PcActionOutcome.Failed -> ActionResult.Failure(
                    done.reason.takeIf { it.isNotBlank() }
                        ?: "Компьютер не смог выполнить «${action.label}»",
                    recoverable = true,
                )
            }

            PcSendOutcome.Rejected -> ActionResult.Failure(PC_DEVICE_REVOKED, recoverable = true)
            is PcSendOutcome.Unreachable ->
                ActionResult.Failure(pcUnreachableText(outcome.why), recoverable = true)
        }
    }
}
