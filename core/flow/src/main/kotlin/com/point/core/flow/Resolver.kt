package com.point.core.flow

import com.point.core.model.CapabilityId

/**
 * Chooses a [Realizer] for a capability at execution time. MVP: the single local
 * realization. Later: pick by [CapabilityMeta] (cost/latency/network/auth) and
 * availability, or route to the Internet Capability Graph — all without any
 * change to the UI or Flow Graph.
 */
interface Resolver {
    fun realizerFor(capabilityId: CapabilityId): Realizer

    /**
     * Может ли этот тап увести объект с устройства.
     *
     * Судится по **реализаторам**, а не по объявленной способности: у «Распознать текст»
     * стоит `network = false` (на устройстве и правда бесплатно и быстро), но за ней —
     * цепочка, где на неудаче движка объект уходил в облако **без согласия**. На корпусе
     * владельца движок не справляется на шести кадрах из двадцати двух, то есть путь этот
     * не редкий, а обычный.
     *
     * Отсюда правило: спрашивает согласие тот, кто МОЖЕТ отправить, а не тот, кто себя таким
     * объявил. Реализация по умолчанию — на самый безопасный ответ: не знаем, значит считаем,
     * что может.
     */
    fun leavesDevice(capabilityId: CapabilityId): Boolean = true
}
