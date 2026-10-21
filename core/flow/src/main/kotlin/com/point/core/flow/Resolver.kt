package com.point.core.flow

import com.point.core.model.CapabilityId

interface Resolver {
    fun realizerFor(capabilityId: CapabilityId): Realizer

    fun realizerFor(capabilityId: CapabilityId, state: com.point.core.model.ObjectState): Realizer =
        realizerFor(capabilityId)

    fun leavesDevice(capabilityId: CapabilityId): Boolean = true

    /**
     * Кто сделает работу первым — тот же выбор, что [realizerFor], без самого исполнителя
     * (#1413): по нему экран называет цену словами выбранного, а не обещанием способности.
     */
    fun chosenFor(capabilityId: CapabilityId, state: com.point.core.model.ObjectState): RealizerMeta? = null
}
