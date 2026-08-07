package com.point.executors

import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject

class FallbackRealizer(
    override val capabilityId: CapabilityId,
    private val chain: List<Realizer>,
) : Realizer {

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        var last: ActionResult = ActionResult.Failure(NOBODY_TO_DO_IT, recoverable = true)
        for ((index, realizer) in chain.withIndex()) {
            val result = realizer.perform(input, amendment)
            last = result
            val defers = result is ActionResult.Failure && result.recoverable
            if (!defers || index == chain.lastIndex) return result
        }
        return last
    }

    internal companion object {

        const val NOBODY_TO_DO_IT = "Это действие сейчас выполнить нечем — вернитесь к объекту и выберите другое"
    }
}
