package com.point.data

import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcPairing
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.model.PointObject

/**
 * «Безотказно» (#161 v2): the phone tries the fast LAN hop first and, only when it can't be reached
 * AND the pairing offers a relay, falls back to the always-works relay (LTE / any network, no
 * inbound port, IP-independent). A *rejected* token is not a reachability failure — it never falls
 * back (re-pairing is the fix). Everything other than [send] stays on LAN; relay receive is a later slice.
 */
class LanThenRelayTransport(
    private val lan: PcTransport,
    private val relay: PcTransport,
) : PcTransport {

    override suspend fun send(
        pairing: PcPairing,
        obj: PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String?,
    ): PcSendOutcome {
        val viaLan = lan.send(pairing, obj, fileName, meta, action)
        return if (viaLan is PcSendOutcome.Unreachable && pairing.relay != null) {
            relay.send(pairing, obj, fileName, meta, action)
        } else {
            viaLan
        }
    }

    /** Пейринг — только по локальной сети: телефон узнаёт токен из QR, а не спрашивает его. */
    override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? =
        lan.pair(host, port, deviceName)

    // Ниже — операции, которые до #161 были LAN-only. Именно из-за этого связь «работала через
    // раз»: вне общей сети телефон не мог ни узнать, что умеет компьютер, ни забрать сделанное им.
    // Теперь каждая пробует локальную сеть и, если её нет, идёт через релей.

    override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? =
        lan.fetchCaps(pairing) ?: pairing.viaRelay { relay.fetchCaps(pairing) }

    override suspend fun fetchOutbox(pairing: PcPairing): List<PcOutboxEntry>? =
        lan.fetchOutbox(pairing) ?: pairing.viaRelay { relay.fetchOutbox(pairing) }

    override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String): Boolean =
        lan.downloadOutboxFile(pairing, id, targetPath) ||
            (pairing.viaRelay { relay.downloadOutboxFile(pairing, id, targetPath) } ?: false)

    override suspend fun ackOutbox(pairing: PcPairing, id: Int) {
        lan.ackOutbox(pairing, id)
        // Подтверждение дублируется намеренно: по локальной сети оно молчаливое (Unit), и понять,
        // дошло ли, нельзя. Повторный ack безвреден — компьютер удаляет уже удалённое молча.
        pairing.viaRelay { relay.ackOutbox(pairing, id) }
    }

    override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>): Boolean =
        lan.pushPhoneCaps(pairing, caps) || (pairing.viaRelay { relay.pushPhoneCaps(pairing, caps) } ?: false)

    /** Релей пробуется, только если он объявлен в этом пейринге, — иначе спрашивать некого. */
    private suspend fun <T> PcPairing.viaRelay(block: suspend () -> T): T? =
        if (relay.isNullOrBlank()) null else block()
}
