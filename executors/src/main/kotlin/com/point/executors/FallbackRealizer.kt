package com.point.executors

import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject

/**
 * Tries an ordered list of realizers, each deferring to the next only on a
 * **recoverable** [ActionResult.Failure]. The first non-deferring result
 * (Success / Done / NeedsInput / hard Failure) wins; if every realizer defers,
 * the last result is returned.
 *
 * This generalises the multi-realizer seam from "pick the best available" to an
 * output-based fallback chain — e.g. on-device OCR that recognises nothing hands
 * off to the cloud. The [com.point.core.flow.Resolver] wraps the ranked, available
 * realizers in this when a capability has more than one; single-realizer
 * capabilities are returned directly, so their behaviour is unchanged.
 */
class FallbackRealizer(
    override val capabilityId: CapabilityId,
    private val chain: List<Realizer>,
) : Realizer {

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        var last: ActionResult = ActionResult.Failure("Нет доступных реализаций", recoverable = true)
        for ((index, realizer) in chain.withIndex()) {
            val result = realizer.perform(input, amendment)
            last = result
            val defers = result is ActionResult.Failure && result.recoverable
            if (!defers || index == chain.lastIndex) return result
        }
        return last
    }
}
