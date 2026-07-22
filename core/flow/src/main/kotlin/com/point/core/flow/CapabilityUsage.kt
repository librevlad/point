package com.point.core.flow

import com.point.core.model.CapabilityId

/**
 * The training signal for a learnable [BubblePolicy]: how often each capability has
 * been applied. [counts] is an in-memory snapshot (no I/O — [BubblePolicy.rank] runs
 * on the render path); [record] persists one application off the render path. The
 * data source is the flow journal, so the policy can grow smarter without the
 * registry, UI, or capabilities changing.
 */
interface CapabilityUsage {
    fun counts(): Map<CapabilityId, Int>
    suspend fun record(id: CapabilityId)
}
