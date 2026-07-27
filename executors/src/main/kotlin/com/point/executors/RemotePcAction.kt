package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.PcPairings
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject

/**
 * A PC-advertised action as a phone bubble (#80): «Открыть на компьютере» sits beside
 * the local actions, indistinguishable in the UI — the realizer ships the object over
 * the existing LAN channel with the action id, and the PC runs it. Synthesised one
 * pair per cached advertisement, exactly like remembered app picks (#66).
 */
class RemotePcCapability(
    private val action: PcRemoteAction,
    private val pairings: PcPairings,
) : Capability {
    override val id = idFor(action)
    override val icon = "pc"
    // LAN hop, not cloud: network=false ON PURPOSE (same reasoning as «На компьютер»).
    override val meta = CapabilityMeta(priority = 76, latency = Latency.FAST)
    override fun label(state: ObjectState) = action.label
    override fun accepts(state: ObjectState) =
        state.kind != ObjectKind.COLLECTION &&
            (action.kinds.isEmpty() || state.kind.name in action.kinds) &&
            pairings.current() != null

    override fun produces(state: ObjectState) = state // terminal — the action happens on the PC

    companion object {
        fun idFor(action: PcRemoteAction) = CapabilityId("pc-do:${action.id}")
    }
}

class RemotePcRealizer(
    private val action: PcRemoteAction,
    private val pairings: PcPairings,
    private val transport: PcTransport,
) : Realizer {
    override val capabilityId = RemotePcCapability.idFor(action)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val pairing = pairings.current()
            ?: return ActionResult.Failure("Компьютер не подключён", recoverable = true)
        val name = input.metadata["name"] ?: "объект"
        return when (val outcome = transport.send(pairing, input, name, input.metadata, action.id)) {
            PcSendOutcome.Sent -> ActionResult.Done("${action.label} — готово")
            PcSendOutcome.Rejected ->
                ActionResult.Failure("Компьютер не узнал это устройство — свяжите устройства заново", recoverable = true)
            is PcSendOutcome.Unreachable ->
                ActionResult.Failure("Компьютер недоступен: ${outcome.detail}", recoverable = true)
        }
    }
}
