package com.point.executors

import com.point.core.flow.Basket
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject
import javax.inject.Inject

/**
 * «В корзину» (#96): the progressive object. A terminal drop — the flow ends, the
 * pile keeps growing across flows, and Home offers to open it as one COLLECTION
 * whose actions apply to everything together (photos+text+pdf → one PDF, share
 * all, …). A collection itself cannot be dropped in — open its items instead.
 */
class BasketCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "basket"
    override val meta = CapabilityMeta(priority = 27)
    override fun label(state: ObjectState) = "В корзину"
    override fun accepts(state: ObjectState) = state.kind.isFileBacked
    override fun produces(state: ObjectState) = state // terminal — the pile is the outcome

    companion object { val ID = CapabilityId("basket") }
}

class BasketRealizer @Inject constructor(
    private val basket: Basket,
) : Realizer {
    override val capabilityId = BasketCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            val count = basket.add(input)
            ActionResult.Done("В корзине: $count. Открыть — с главного экрана")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось добавить в корзину", recoverable = true) }
}
