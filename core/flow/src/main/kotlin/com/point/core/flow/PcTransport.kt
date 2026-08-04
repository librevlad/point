package com.point.core.flow

import com.point.core.model.PointObject

/** Outcome of a phone→PC send — mapped to user-facing ActionResults by the realizer. */
sealed interface PcSendOutcome {
    /**
     * Байты доехали.
     *
     * [action] — что компьютер ответил про **заказанное действие**; `null` значит «неизвестно»:
     * действия не заказывали, компьютер старой сборки об исходе молчит или ещё не закончил.
     * Доставка и выполнение — разные события, и склеивать их нельзя: «файл доехал» ≠ «напечатано».
     */
    data class Sent(val action: PcActionOutcome? = null) : PcSendOutcome

    /** 401/403 — the PC no longer trusts our token; re-pairing is needed. */
    data object Rejected : PcSendOutcome
    data class Unreachable(val detail: String) : PcSendOutcome
}

/**
 * Чем кончилось действие **на компьютере** (#114).
 *
 * Раньше телефон говорил «Напечатать на ПК — готово» в ответ на 200 от доставки файла: компьютер
 * исход выбрасывал (`runCatching { runAction(...) }`), а телефон переводил «доехало» в «готово».
 * Человек уходил в другую комнату к принтеру, которого нет.
 */
sealed interface PcActionOutcome {
    /** Сделано. [detail] — слова самого компьютера («В очереди «HP» · проверьте принтер»). */
    data class Done(val detail: String? = null) : PcActionOutcome

    /** Не вышло, и причина названа компьютером — телефон её не сочиняет и не сглаживает. */
    data class Failed(val reason: String) : PcActionOutcome
}

/** The phone's side of the LAN protocol (#147); HTTP details live behind this seam. */
interface PcTransport {
    /** Asks the PC to pair (blocks until the user answers on the PC, up to ~60s). */
    suspend fun pair(host: String, port: Int, deviceName: String): PcPairing?

    suspend fun send(
        pairing: PcPairing,
        obj: PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String? = null,
    ): PcSendOutcome

    /** The PC's advertised remote actions (#80), or null when it is unreachable. */
    suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>?

    /** The PC's outbox listing (#161), or null when it is unreachable. */
    suspend fun fetchOutbox(pairing: PcPairing): List<PcOutboxEntry>?

    /** Stream one outbox entry into [targetPath]; false on any failure. */
    suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String): Boolean

    /** Confirm the entry landed — the PC drops it from the outbox. */
    suspend fun ackOutbox(pairing: PcPairing, id: Int)

    /** Advertise the phone's own actions to the PC (#161 v2) — its cards grow
     *  «… · телефон» buttons. Quiet best-effort. */
    suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>): Boolean
}

/** Cached remote actions of the paired PC — warm sync read for capability synthesis
 *  at process start (#80); refreshed on pairing, dropped on unpair. */
interface PcCapsStore {
    fun all(): List<PcRemoteAction>
    suspend fun save(caps: List<PcRemoteAction>)
    suspend fun clear()
}

/** The remembered PC, warm sync read like the other tiny stores. */
interface PcPairings {
    fun current(): PcPairing?
    suspend fun save(pairing: PcPairing)
    suspend fun clear()
}
