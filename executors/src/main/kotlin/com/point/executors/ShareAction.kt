package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Sharer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import javax.inject.Inject

class ShareCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "share"
    override val meta = CapabilityMeta(priority = 80)
    override fun label(state: ObjectState) = "Поделиться"

    /**
     * Поделиться можно всем, кроме набора (#820, решение владельца 12.08.2026).
     *
     * Мерка «есть ли файл» отсекала найденные значения — телефон, почту, адрес, дату: файла
     * у них нет, но отправить их есть чем, значение уходит текстом (`AndroidSharer`, #584).
     * Живой прогон: «для телефона так и не появилось функции поделиться — не могу отправить
     * в гетконтакт». У набора своё действие — «Поделиться всем».
     */
    override fun accepts(state: ObjectState) = state.kind != ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = state

    companion object { val ID = CapabilityId("share") }
}

class ShareRealizer @Inject constructor(
    private val sharer: Sharer,
) : Realizer {
    override val capabilityId = ShareCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            sharer.share(input)
            ActionResult.Done("Открыт диалог «Поделиться»")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось поделиться", recoverable = true) }
}
