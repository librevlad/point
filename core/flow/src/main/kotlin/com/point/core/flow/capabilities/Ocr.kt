package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

class OcrCapability : Capability {
    override val id = ID
    override val icon = "ocr"

    // Отвечает на вопрос «что написано на кадре»: при уже прочитанном уходит вниз (#1119).
    override val meta = CapabilityMeta(
        cost = Cost.FREE,
        latency = Latency.SLOW,
        answers = com.point.core.flow.KnownCapabilities.IMAGE_TEXT,
    )

    override fun label(state: ObjectState) = "Распознать текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override fun yields(state: ObjectState) =
        ActionYield.New(ObjectKind.TEXT, "текст · сначала на телефоне, потом спрошу про сервис")

    companion object { val ID = CapabilityId("ocr") }
}
