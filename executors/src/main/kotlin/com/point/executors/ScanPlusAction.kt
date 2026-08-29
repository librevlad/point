package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import javax.inject.Inject

class ScanPlusCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "scan"
    override val meta = CapabilityMeta(latency = Latency.SLOW)

    override fun label(state: ObjectState) = "Скан с цветом"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    override fun yields(state: ObjectState) =
        ActionYield.New(ObjectKind.IMAGE, "картинку · дольше, зато на устройстве")

    companion object { val ID = CapabilityId("scan-plus") }
}
