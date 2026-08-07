package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.PC_DEVICE_REVOKED
import com.point.core.flow.PcLinks
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.PcUnreachable
import com.point.core.flow.Realizer
import com.point.core.flow.pcUnreachableText
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject
import javax.inject.Inject

class PcCapability @Inject constructor(
    private val links: PcLinks,
) : Capability {
    override val id = ID
    override val icon = "pc"

    override val meta = CapabilityMeta(priority = 75, latency = Latency.FAST, localOnly = true)
    override fun label(state: ObjectState) = "На компьютер"

    override fun accepts(state: ObjectState) =
        state.kind != ObjectKind.COLLECTION && links.current() != null

    override fun produces(state: ObjectState) = state
    override fun missing(state: ObjectState) =
        if (links.current() == null && state.kind != ObjectKind.COLLECTION) "войдите в аккаунт на компьютере" else null

    companion object { val ID = CapabilityId("pc") }
}

internal const val PC_SEND_STAGE = "Отправляю на компьютер"

class PcRealizer @Inject constructor(
    private val links: PcLinks,
    private val transport: PcTransport,
) : Realizer {
    override val capabilityId = PcCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val pc = links.current()
            ?: return ActionResult.Failure(pcUnreachableText(PcUnreachable.NOT_IN_CIRCLE), recoverable = true)
        val name = input.metadata["name"] ?: "point-${input.id.take(8)}"

        reportStage(PC_SEND_STAGE)
        return when (val outcome = transport.send(pc, input, name, input.metadata)) {
            is PcSendOutcome.Sent -> ActionResult.Done("Отправлено на компьютер")
            is PcSendOutcome.Rejected -> ActionResult.Failure(PC_DEVICE_REVOKED, recoverable = true)

            is PcSendOutcome.Unreachable ->
                ActionResult.Failure(pcUnreachableText(outcome.why), recoverable = true)
        }
    }
}
