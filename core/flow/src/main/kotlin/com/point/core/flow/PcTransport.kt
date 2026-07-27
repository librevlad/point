package com.point.core.flow

import com.point.core.model.PointObject

/** Outcome of a phone→PC send — mapped to user-facing ActionResults by the realizer. */
sealed interface PcSendOutcome {
    data object Sent : PcSendOutcome

    /** 401/403 — the PC no longer trusts our token; re-pairing is needed. */
    data object Rejected : PcSendOutcome
    data class Unreachable(val detail: String) : PcSendOutcome
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
