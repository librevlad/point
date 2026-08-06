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
     * То же, но с оглядкой на **сам объект** (контракт 06.08.2026, И3).
     *
     * У одной способности реализации бывают разной ширины: PDF из офисного документа делает и
     * телефон, и компьютер, а из картинки — только телефон. Без объекта такой выбор сделать
     * нечем, и до этого шва его делали за `Resolver` — сужая саму способность, то есть отнимая её
     * у второго устройства целиком.
     *
     * Умолчание отдаёт вопрос старому [realizerFor]: ни одна из существующих реализаций
     * контракта не обязана меняться, чтобы продолжать работать.
     */
    fun realizerFor(capabilityId: CapabilityId, state: com.point.core.model.ObjectState): Realizer =
        realizerFor(capabilityId)

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
