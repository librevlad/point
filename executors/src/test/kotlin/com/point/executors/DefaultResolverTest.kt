package com.point.executors

import com.point.core.flow.Entitlements
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM: the multi-realizer selection seam (local/cloud/ICG) and the paywall
 *  gate (a PAID capability resolves to an upsell when the user is not entitled). */
class DefaultResolverTest {

    // Registry with the real capabilities, so the paywall gate can read their cost.
    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(AiCapability(aiKeysReady), SaveCapability()),
        policy = DefaultBubblePolicy(),
    )

    private fun realizer(
        id: String,
        priority: Int = 50,
        kind: RealizerKind = RealizerKind.LOCAL,
        available: Boolean = true,
        done: String = "x",
        result: ActionResult? = null,
    ) = object : Realizer {
        override val capabilityId = CapabilityId(id)
        override val meta = RealizerMeta(priority, kind)
        override fun isAvailable() = available
        override suspend fun perform(input: PointObject, amendment: String?) =
            result ?: ActionResult.Done(done)
    }

    /** entitled = true keeps the paywall transparent, so selection tests are unchanged. */
    private fun resolver(realizers: Set<Realizer>, entitled: Boolean = true) =
        DefaultResolver(realizers, registry, Entitlements { entitled })

    private fun obj() = PointObject("x", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    // --- multi-realizer selection ---

    @Test
    fun `the lowest-priority available realizer is tried first`() = runTest {
        val local = realizer("ocr", priority = 10, done = "local")
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, done = "cloud")
        val result = resolver(setOf(cloud, local)).realizerFor(CapabilityId("ocr")).perform(obj())
        assertEquals("local", (result as ActionResult.Done).message)
    }

    @Test
    fun `multiple available realizers fall through a recoverable failure to the next`() = runTest {
        val local = realizer("ocr", priority = 10, result = ActionResult.Failure("miss", recoverable = true))
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, done = "cloud")
        val result = resolver(setOf(local, cloud)).realizerFor(CapabilityId("ocr")).perform(obj())
        assertTrue(result is ActionResult.Done)
        assertEquals("cloud", (result as ActionResult.Done).message)
    }

    @Test
    fun `preferred but unavailable falls through to the next available`() {
        val local = realizer("ocr", priority = 10, available = false)
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, available = true)
        assertSame(cloud, resolver(setOf(local, cloud)).realizerFor(CapabilityId("ocr")))
    }

    @Test
    fun `kind breaks priority ties — local is tried before cloud`() = runTest {
        val local = realizer("ai", kind = RealizerKind.LOCAL, done = "local")
        val cloud = realizer("ai", kind = RealizerKind.CLOUD, done = "cloud")
        val result = resolver(setOf(cloud, local)).realizerFor(CapabilityId("ai")).perform(obj())
        assertEquals("local", (result as ActionResult.Done).message)
    }

    @Test
    fun `when none is available it returns the top-ranked so perform surfaces the error`() {
        val local = realizer("ocr", priority = 10, available = false)
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, available = false)
        assertSame(local, resolver(setOf(cloud, local)).realizerFor(CapabilityId("ocr")))
    }

    @Test
    fun `unknown capability id throws`() {
        assertThrows(IllegalStateException::class.java) {
            resolver(setOf(realizer("ocr"))).realizerFor(CapabilityId("nope"))
        }
    }

    // --- paywall gate ---

    @Test
    fun `a paid capability resolves to an upsell when not entitled`() = runTest {
        val r = resolver(setOf(realizer("ai", done = "real AI")), entitled = false)
        val result = r.realizerFor(AiCapability.ID).perform(obj())
        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `a paid capability passes through to the real realizer when entitled`() = runTest {
        val r = resolver(setOf(realizer("ai", done = "real AI")), entitled = true)
        val result = r.realizerFor(AiCapability.ID).perform(obj())
        assertEquals("real AI", (result as ActionResult.Done).message)
    }

    @Test
    fun `a free capability is never gated`() = runTest {
        val r = resolver(setOf(realizer("save", done = "saved")), entitled = false)
        val result = r.realizerFor(SaveCapability.ID).perform(obj())
        assertEquals("saved", (result as ActionResult.Done).message)
    }
}
