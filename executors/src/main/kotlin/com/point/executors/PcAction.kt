package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.PcPairings
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * «На компьютер» (#147) — the first Liquid Software edge: the object (bytes AND its
 * understanding) crosses to the paired PC. Hidden until a pairing exists, with a
 * latent hint teaching the feature (#97). `network=false` on purpose: the LAN hop
 * never leaves the user's devices, so the cloud-consent gate (#10) must not fire.
 */
class PcCapability @Inject constructor(
    private val pairings: PcPairings,
) : Capability {
    override val id = ID
    override val icon = "pc"
    override val meta = CapabilityMeta(priority = 75, latency = Latency.FAST)
    override fun label(state: ObjectState) = "На компьютер"
    override fun accepts(state: ObjectState) =
        state.kind.isFileBacked && pairings.current() != null

    override fun produces(state: ObjectState) = state // terminal — the object leaves for the PC
    override fun missing(state: ObjectState) =
        if (pairings.current() == null && state.kind != ObjectKind.COLLECTION) "подключите компьютер" else null

    companion object { val ID = CapabilityId("pc") }
}

class PcRealizer @Inject constructor(
    private val pairings: PcPairings,
    private val transport: PcTransport,
) : Realizer {
    override val capabilityId = PcCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val pairing = pairings.current()
            ?: return ActionResult.Failure("Компьютер не подключён", recoverable = true)
        val name = input.metadata["name"] ?: "point-${input.id.take(8)}"
        return when (val outcome = transport.send(pairing, input, name, input.metadata)) {
            is PcSendOutcome.Sent -> ActionResult.Done("Отправлено на компьютер")
            is PcSendOutcome.Rejected ->
                ActionResult.Failure("Компьютер отклонил — свяжите устройства заново", recoverable = true)
            is PcSendOutcome.Unreachable ->
                ActionResult.Failure(
                    "Компьютер недоступен. Проверьте, что «Point для ПК» запущен, а порт открыт " +
                        "в брандмауэре Windows (или сделайте Wi-Fi сеть «Частной»).",
                    recoverable = true,
                )
        }
    }
}
