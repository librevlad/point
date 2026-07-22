package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject

/**
 * One concrete way to perform a [Capability] — the **how**. Today every
 * capability has a single local realizer; tomorrow the same capability id may
 * have several (local, AI, cloud, ICG) and the [Resolver] picks one. Nothing
 * above the resolver (UI, graph, policy) is aware of realizers.
 */
interface Realizer {

    val capabilityId: CapabilityId

    /** Selection traits (see [RealizerMeta]). Default = a plain local realizer. */
    val meta: RealizerMeta get() = RealizerMeta()

    /**
     * Whether this realization can run right now — e.g. a cloud realizer with no
     * API key, or an offline device, returns false so the [Resolver] skips it.
     * Local realizers are available by default.
     */
    fun isAvailable(): Boolean = true

    /** Cancellable work. [amendment] is the user's optional free-text addition. */
    suspend fun perform(input: PointObject, amendment: String? = null): ActionResult
}
