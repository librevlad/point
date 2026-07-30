package com.point.data

import com.point.core.flow.ClipPull
import com.point.core.flow.ClipPush
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcPairing

/**
 * Shared clipboard «безотказно» (#161 «общий буфер»): the phone syncs over the fast LAN hop first and,
 * only when the PC can't be reached AND the pairing offers a relay, falls back to the always-works
 * relay (LTE / any network, no inbound port). A *reachable-but-empty* PC clipboard ([ClipPull.Empty])
 * is a real answer, not a reachability failure — it never falls back. A terminal [ClipPush.Failed] /
 * [ClipPull.Failed] (blob over the cap, stale secret, pinning miss — #272) doesn't fall back either:
 * no other transport can shrink a payload or fix a key. Mirrors [LanThenRelayTransport].
 */
class LanThenRelayClipboardSync(
    private val lan: PcClipboardSync,
    private val relay: PcClipboardSync,
) : PcClipboardSync {

    override suspend fun push(pairing: PcPairing, payload: ClipboardPayload): ClipPush {
        val viaLan = lan.push(pairing, payload)
        return if (viaLan is ClipPush.Unreachable && pairing.relay != null) relay.push(pairing, payload) else viaLan
    }

    override suspend fun pull(pairing: PcPairing): ClipPull {
        val viaLan = lan.pull(pairing)
        return if (viaLan is ClipPull.Unreachable && pairing.relay != null) relay.pull(pairing) else viaLan
    }
}
