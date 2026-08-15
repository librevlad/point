package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

/**
 * @param readsHere умеет ли это устройство читать кадр само (#1021).
 *
 * Обещание принадлежит тому, кто будет исполнять, а не общему словарю: на компьютере
 * «текст · сначала на телефоне, потом спрошу про сервис» — неправда, своего чтения у него
 * нет, и в закрытом режиме человек читал обещание шага, которого не будет, а по тапу
 * получал «Наружу сейчас не отправляем».
 */
class OcrCapability(private val readsHere: Boolean = true) : Capability {
    override val id = ID
    override val icon = "ocr"

    override val meta = CapabilityMeta(cost = Cost.FREE, latency = Latency.SLOW)

    override fun label(state: ObjectState) = "Распознать текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    override fun yields(state: ObjectState) = ActionYield.New(
        ObjectKind.TEXT,
        if (readsHere) "текст · сначала на телефоне, потом спрошу про сервис" else "текст · спрошу про сервис",
    )

    companion object { val ID = CapabilityId("ocr") }
}
