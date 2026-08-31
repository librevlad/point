package com.point

import com.point.core.flow.CircleDevice
import com.point.core.flow.LinkMonitor
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcCapsStore
import com.point.core.flow.PcLinks
import com.point.core.flow.PcTransport
import com.point.core.flow.SharedSecrets
import com.point.core.flow.UserAiKey

/**
 * Всё, чем живёт связка с компьютером: где он, чем говорить, что он умеет (#833, шаг 4).
 *
 * Пять зависимостей ядру поодиночке не нужны — они нужны одной теме и ходят вместе.
 */
class PcParts @javax.inject.Inject constructor(
    val links: PcLinks,
    val transport: PcTransport,
    val caps: PcCapsStore,
    val monitor: LinkMonitor,
    val pulledFiles: com.point.PulledFileFactory,
)

/**
 * Связка с компьютером живёт своим держателем (#833, шаг 4).
 *
 * Четвёртый шаг разреза `FlowViewModel` — после разговора, аккаунта и настроек. Уезжает то,
 * что и есть тема связки: какой компьютер связан, рядом ли он, что он умеет, обмен общим
 * ключом.
 *
 * Ядро при этом не режется, и это не осторожность, а решение карточки: приём объекта, список
 * действий и выполнение — один поток. Забор объекта с компьютера (`pullFromPc`) и отправка
 * исхода просьбы — это приём и выполнение, а не связка, и они остаются там же, где были;
 * держатель даёт им дорогу (`transport`), а не забирает их себе.
 */
class PcFlow(private val parts: PcParts) {

    /** Связанный компьютер или `null` — связки нет. */
    fun current(): LinkedPc? = runCatching { parts.links.current() }.getOrNull()

    /** Чем говорить с компьютером: тот же транспорт, что и у приёма объекта. */
    val transport: PcTransport get() = parts.transport

    /** Куда класть забранное с компьютера. */
    val pulledFiles: com.point.PulledFileFactory get() = parts.pulledFiles

    /** Что компьютер о себе объявил. */
    fun caps() = parts.caps

    /**
     * Компьютер отозвался — это и есть его живость (#545).
     *
     * Записывает её тот, кто её видел; по ней продолжение на компьютере ведёт список, пока
     * машина рядом.
     */
    fun heard() {
        runCatching { parts.monitor.heard() }
    }

    /** Компьютера больше нет: связка, его объявление и память о встречах уходят вместе. */
    suspend fun forget() {
        runCatching { parts.links.clear() }
        runCatching { parts.caps.clear() }
        runCatching { parts.monitor.forget() }
    }

    /**
     * Компьютер из круга — тот, что отзывался последним (#1076).
     *
     * Компьютера в круге нет — связка снимается: держать её значило бы говорить с машиной,
     * которой у человека больше нет.
     *
     * Возвращает `true`, если связка сменилась: тогда за объектами к компьютеру стоит сходить
     * заново, и знает об этом ядро, а не эта тема.
     */
    suspend fun remember(
        devices: List<CircleDevice>,
        exchangeSecrets: suspend (LinkedPc) -> Unit,
        advertised: () -> List<com.point.core.flow.PcRemoteAction>,
    ): Boolean {
        val pc = devices
            .filter { !it.self && it.kind == com.point.core.flow.DeviceKind.PC }
            .maxByOrNull { it.lastSeenMillis ?: 0L }
        if (pc == null) {
            runCatching { parts.links.clear() }
            runCatching { parts.caps.clear() }
            return false
        }

        // Когда компьютер отзывался в последний раз, знает сервер — здесь это знание и
        // записывается (#545): по нему продолжение на нём ведёт список, пока он рядом.
        pc.lastSeenMillis
            ?.takeIf { System.currentTimeMillis() - it in 0..com.point.core.flow.PC_AWAKE_WITHIN_MS }
            ?.let { heard() }

        val known = LinkedPc(pc.id, pc.name, pc.key)
        runCatching { exchangeSecrets(known) }

        // Объявления обеих сторон освежаются и для давно известного ПК: он мог обновиться,
        // пока связь жила, — «На телефон на ПК» держалось у телефона кэшем вечно, до захода
        // в «Устройства» (#627, скрин владельца 2026-08-09).
        runCatching { parts.transport.fetchCaps(known)?.let { caps -> parts.caps.save(caps) } }
        runCatching { parts.transport.pushPhoneCaps(known, advertised()) }

        if (current() == known) return false
        runCatching { parts.links.save(known) }
        return true
    }

    /**
     * Объявление компьютера освежается в фоне, когда оно постарело.
     *
     * Свежее не перечитывается: список действий человек видит каждый раз, а компьютер
     * обновляется редко.
     */
    suspend fun refreshCapsIfStale() {
        val pc = current() ?: return
        if (com.point.core.flow.capsFresh(parts.caps.savedAt(), System.currentTimeMillis())) return
        val fresh = runCatching { parts.transport.fetchCaps(pc) }.getOrNull() ?: return
        runCatching { parts.caps.save(fresh) }
    }

    /**
     * Общий ключ сервиса — один на круг устройств.
     *
     * Отдаём свой самый свежий, забираем чужой, если он новее. Ключ с компьютера ложится к
     * тому же сервису, что и здешний: чужой сервис ему приписывать не за что.
     */
    suspend fun exchangeSecrets(pc: LinkedPc, mineKey: UserAiKey?): UserAiKey? {
        val mine = SharedSecrets(aiKey = mineKey?.apiKey.orEmpty(), at = mineKey?.savedAt ?: 0L)
        val merged = parts.transport.exchangeSecrets(pc, mine) ?: return null
        if (merged.aiKey.isBlank() || merged.aiKey == mine.aiKey) return null
        return UserAiKey(
            providerId = mineKey?.providerId ?: com.point.core.flow.AI_PROVIDERS.first().id,
            apiKey = merged.aiKey,
            model = mineKey?.model.orEmpty(),
            baseUrl = mineKey?.baseUrl.orEmpty(),
            savedAt = merged.at,
        )
    }
}
