package com.point.data

import com.point.core.flow.ClipPull
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcClipboardSync
import com.point.core.flow.PcPairing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared clipboard «безотказно» (#161): LAN when it can be reached, the relay whenever it can't — and
 *  never the relay for a reachable-but-empty PC clipboard. Mirrors [LanThenRelayTransportTest]. */
class LanThenRelayClipboardSyncTest {

    private open class Fake(
        private val pushResult: Boolean = false,
        private val pullResult: ClipPull = ClipPull.Unreachable,
    ) : PcClipboardSync {
        var pushed = false
        var pulled = false
        override suspend fun push(pairing: PcPairing, payload: ClipboardPayload): Boolean {
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
        val relay = Fake(pushResult = true)
        assertTrue(LanThenRelayClipboardSync(Fake(pushResult = true), relay).push(withRelay, payload))
        assertFalse("relay must not be used when LAN push works", relay.pushed)
    }

    @Test
    fun `lan push failure with a relay falls back`() = runTest {
        val relay = Fake(pushResult = true)
        assertTrue(LanThenRelayClipboardSync(Fake(pushResult = false), relay).push(withRelay, payload))
        assertTrue(relay.pushed)
    }

    @Test
    fun `lan push failure without a relay does not fall back`() = runTest {
        val relay = Fake(pushResult = true)
        assertFalse(LanThenRelayClipboardSync(Fake(pushResult = false), relay).push(noRelay, payload))
        assertFalse(relay.pushed)
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
}
