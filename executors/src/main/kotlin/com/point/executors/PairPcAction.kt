package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.PcPairings
import com.point.core.flow.Realizer
import com.point.core.flow.parsePcPairing
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Подключить компьютер» (#147): a pairing payload that arrived as TEXT (photographed
 * QR → read-qr, or just shared) becomes the pairing itself — one tap, no camera.
 */
class PairPcCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "pc"
    override val meta = CapabilityMeta(priority = 20)
    override fun label(state: ObjectState) = "Подключить компьютер"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.TEXT && Feature.HAS_PC_PAIRING in state.features

    override fun produces(state: ObjectState) = state // terminal — the pairing is the outcome

    companion object { val ID = CapabilityId("pair-pc") }
}

class PairPcRealizer @Inject constructor(
    private val pairings: PcPairings,
) : Realizer {
    override val capabilityId = PairPcCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            val payload = input.metadata["pc.pairing"]
                ?: runCatching { File(input.uri.value).readText().trim() }.getOrDefault("")
            val pairing = parsePcPairing(payload)
                ?: return@withContext ActionResult.Failure("Это не код подключения Point для ПК", recoverable = true)
            runCatching { pairings.save(pairing) }
                .getOrElse { return@withContext ActionResult.Failure("Не удалось сохранить подключение", recoverable = true) }
            ActionResult.Done("Компьютер подключён: ${pairing.host}:${pairing.port}")
        }
}
