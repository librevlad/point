package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState

interface Capability {

    val id: CapabilityId

    val icon: String

    val meta: CapabilityMeta get() = CapabilityMeta()

    fun label(state: ObjectState): String

    fun accepts(state: ObjectState): Boolean

    fun produces(state: ObjectState): ObjectState?

    fun yields(state: ObjectState): ActionYield = derivedYield(this, state)

    fun intents(state: ObjectState): Set<Intent> {
        val next = produces(state)
        return when {
            next == null -> setOf(Intent.UNDERSTAND)
            next === state -> setOf(Intent.SEND)
            next.kind == ObjectKind.TEXT -> setOf(Intent.UNDERSTAND)
            else -> setOf(Intent.PREPARE)
        }
    }

    fun missing(state: ObjectState): String? = null
}
