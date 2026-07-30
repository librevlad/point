package com.point.data

import com.point.core.flow.ClipFail
import com.point.core.flow.ClipPull
import com.point.core.flow.ClipPush
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcPairing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared clipboard «безотказно» (#161): LAN when it can be reached, the relay whenever it can't — and
 *  never the relay for a reachable-but-empty PC clipboard. A terminal [ClipPush.Failed]/[ClipPull.Failed]
 *  (#272) never falls back either: no transport can shrink a payload or fix a stale key.
 *  Mirrors [LanThenRelayTransportTest]. */
class LanThenRelayClipboardSyncTest {

    private open class Fake(
        private val pushResult: ClipPush = ClipPush.Unreachable,
        private val pullResult: ClipPull = ClipPull.Unreachable,
    ) : PcClipboardSync {
        var pushed = false
        var pulled = false
        override suspend fun push(pairing: PcPairing, payload: ClipboardPayload): ClipPush {
            pushed = true; return pushResult
        }
        override suspend fun pull(pairing: PcPairing): ClipPull {
            pulled = true; return pullResult
        }
    }

    private val payload = ClipboardPayload.ofText("x")
    private val noRelay = PcPairing("h", 1, "t")
    private val withRelay = PcPairing("h", 1, "t", relay = "https://s:8443")

    @Test
    fun `lan push success never touches relay`() = runTest {
        val relay = Fake(pushResult = ClipPush.Sent)
        val r = LanThenRelayClipboardSync(Fake(pushResult = ClipPush.Sent), relay).push(withRelay, payload)
        assertEquals(ClipPush.Sent, r)
        assertFalse("relay must not be used when LAN push works", relay.pushed)
    }

    @Test
    fun `lan push unreachable with a relay falls back`() = runTest {
        val relay = Fake(pushResult = ClipPush.Sent)
        val r = LanThenRelayClipboardSync(Fake(pushResult = ClipPush.Unreachable), relay).push(withRelay, payload)
        assertEquals(ClipPush.Sent, r)
        assertTrue(relay.pushed)
    }

    @Test
    fun `lan push unreachable without a relay does not fall back`() = runTest {
        val relay = Fake(pushResult = ClipPush.Sent)
        val r = LanThenRelayClipboardSync(Fake(pushResult = ClipPush.Unreachable), relay).push(noRelay, payload)
        assertEquals(ClipPush.Unreachable, r)
        assertFalse(relay.pushed)
    }

    /** #272: релей отверг полезную нагрузку (413) — это терминально, LAN её тоже не примет короче. */
    @Test
    fun `terminal push failure from the relay surfaces as-is`() = runTest {
        val relay = Fake(pushResult = ClipPush.Failed(ClipFail.TOO_BIG))
        val r = LanThenRelayClipboardSync(Fake(pushResult = ClipPush.Unreachable), relay).push(withRelay, payload)
        assertEquals(ClipPush.Failed(ClipFail.TOO_BIG), r)
    }

    @Test
    fun `lan pull empty never touches relay`() = runTest {
        val relay = Fake(pullResult = ClipPull.Got(payload))
        val r = LanThenRelayClipboardSync(Fake(pullResult = ClipPull.Empty), relay).pull(withRelay)
        assertEquals(ClipPull.Empty, r)
        assertFalse("a reachable-but-empty PC must not trigger the relay", relay.pulled)
    }

    @Test
    fun `lan pull unreachable with a relay falls back`() = runTest {
        val relay = Fake(pullResult = ClipPull.Got(payload))
        val r = LanThenRelayClipboardSync(Fake(pullResult = ClipPull.Unreachable), relay).pull(withRelay)
        assertTrue(r is ClipPull.Got)
        assertTrue(relay.pulled)
    }

    @Test
    fun `lan pull unreachable without a relay stays unreachable`() = runTest {
        val r = LanThenRelayClipboardSync(Fake(pullResult = ClipPull.Unreachable), Fake()).pull(noRelay)
        assertEquals(ClipPull.Unreachable, r)
    }

    /** #272: терминальный отказ релея (протухший ключ) доходит до вызывающего, а не маскируется. */
    @Test
    fun `terminal pull failure from the relay surfaces as-is`() = runTest {
        val relay = Fake(pullResult = ClipPull.Failed(ClipFail.AUTH))
        val r = LanThenRelayClipboardSync(Fake(pullResult = ClipPull.Unreachable), relay).pull(withRelay)
        assertEquals(ClipPull.Failed(ClipFail.AUTH), r)
    }
}
