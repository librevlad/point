package com.point.data

import com.point.core.flow.DiscoveredPc
import com.point.core.flow.PcDiscovery
import com.point.core.flow.PcOutboxEntry
import com.point.core.flow.PcPairing
import com.point.core.flow.PcPairings
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.model.PointObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Discovered PCs worth retrying when the saved address is unreachable: those whose address differs
 *  from the current [pairing] — retrying the same dead host:port is pointless. Pure — JVM-tested. */
fun pcHealCandidates(pairing: PcPairing, discovered: List<DiscoveredPc>): List<DiscoveredPc> =
    discovered.filter { it.host != pairing.host || it.port != pairing.port }

/**
 * Self-healing LAN transport (#161 v2, «железобетонно»). The #1 cause of "работает через раз" is a
 * **stale saved IP** — a DHCP re-lease, the PC moving networks, or port drift freezes `host:port`
 * while the PC is actually reachable at a new address (the pairing is never re-resolved). On an
 * `Unreachable` send this re-resolves the PC via mDNS and retries each fresh address **with the same
 * token**; the PC that accepts (200 → [PcSendOutcome.Sent]) is ours — its address is then remembered,
 * so every later call uses it. The token is the identity, so a stranger's PC is never adopted, and a
 * `Rejected` token (re-pair signal) is never treated as a reachability failure. All else delegates.
 */
class SelfHealingPcTransport(
    private val lan: PcTransport,
    private val discovery: PcDiscovery,
    private val pairings: PcPairings,
    private val scanTimeoutMs: Long = 4000,
    private val retryDelayMs: Long = 400,
) : PcTransport {

    override suspend fun send(
        pairing: PcPairing,
        obj: PointObject,
        fileName: String,
        meta: Map<String, String>,
        action: String?,
    ): PcSendOutcome {
        val outcome = lan.send(pairing, obj, fileName, meta, action)
        if (outcome !is PcSendOutcome.Unreachable) return outcome
        // A transient Wi-Fi hiccup on the current address? one quick retry before assuming it's stale.
        delay(retryDelayMs)
        val retry = lan.send(pairing, obj, fileName, meta, action)
        if (retry !is PcSendOutcome.Unreachable) return retry
        // Still unreachable — the saved address is likely stale; re-resolve via mDNS.
        for (pc in pcHealCandidates(pairing, snapshot())) {
            val healed = pairing.copy(host = pc.host, port = pc.port)
            val sent = lan.send(healed, obj, fileName, meta, action)
            if (sent is PcSendOutcome.Sent) {
                runCatching { pairings.save(healed) } // remember the address that actually worked
                // Возвращаем ИМЕННО тот ответ, что дал компьютер: исход действия ехал в нём, и
                // пересобранный «Sent» стирал бы его — телефон снова говорил бы «готово» вслепую.
                return sent
            }
        }
        return outcome
    }

    /** A bounded mDNS snapshot — the first non-empty list within [scanTimeoutMs], else empty. */
    private suspend fun snapshot(): List<DiscoveredPc> =
        withTimeoutOrNull(scanTimeoutMs) { discovery.discover().first { it.isNotEmpty() } } ?: emptyList()

    override suspend fun pair(host: String, port: Int, deviceName: String): PcPairing? =
        lan.pair(host, port, deviceName)

    override suspend fun fetchCaps(pairing: PcPairing): List<PcRemoteAction>? = lan.fetchCaps(pairing)

    override suspend fun fetchOutbox(pairing: PcPairing): List<PcOutboxEntry>? = lan.fetchOutbox(pairing)

    override suspend fun downloadOutboxFile(pairing: PcPairing, id: Int, targetPath: String): Boolean =
        lan.downloadOutboxFile(pairing, id, targetPath)

    override suspend fun ackOutbox(pairing: PcPairing, id: Int) = lan.ackOutbox(pairing, id)

    override suspend fun pushPhoneCaps(pairing: PcPairing, caps: List<PcRemoteAction>): Boolean =
        lan.pushPhoneCaps(pairing, caps)
}
