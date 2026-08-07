package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Preview

interface Realizer {

    val capabilityId: CapabilityId

    val meta: RealizerMeta get() = RealizerMeta()

    fun isAvailable(): Boolean = true

    fun accepts(state: ObjectState): Boolean = true

    fun unavailableReason(): String? = null

    suspend fun perform(input: PointObject, amendment: String? = null): ActionResult

    suspend fun preview(input: PointObject): Preview? = null
}
