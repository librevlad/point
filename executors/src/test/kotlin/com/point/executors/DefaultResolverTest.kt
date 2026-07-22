package com.point.executors

import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure-JVM: the multi-realizer selection seam (local/cloud/ICG). */
class DefaultResolverTest {

    private fun realizer(
        id: String,
        priority: Int = 50,
        kind: RealizerKind = RealizerKind.LOCAL,
        available: Boolean = true,
    ) = object : Realizer {
        override val capabilityId = CapabilityId(id)
        override val meta = RealizerMeta(priority, kind)
        override fun isAvailable() = available
        override suspend fun perform(input: PointObject, amendment: String?) = ActionResult.Done("x")
    }

    @Test
    fun `lowest priority wins among available`() {
        val local = realizer("ocr", priority = 10)
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD)
        val resolver = DefaultResolver(setOf(cloud, local))
        assertSame(local, resolver.realizerFor(CapabilityId("ocr")))
    }

    @Test
    fun `preferred but unavailable falls through to the next available`() {
        val local = realizer("ocr", priority = 10, available = false)
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, available = true)
        val resolver = DefaultResolver(setOf(local, cloud))
        assertSame(cloud, resolver.realizerFor(CapabilityId("ocr")))
    }

    @Test
    fun `kind breaks priority ties — local before cloud`() {
        val local = realizer("ai", kind = RealizerKind.LOCAL)
        val cloud = realizer("ai", kind = RealizerKind.CLOUD)
        val resolver = DefaultResolver(setOf(cloud, local))
        assertSame(local, resolver.realizerFor(CapabilityId("ai")))
    }

    @Test
    fun `when none is available it returns the top-ranked so perform surfaces the error`() {
        val local = realizer("ocr", priority = 10, available = false)
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, available = false)
        val resolver = DefaultResolver(setOf(cloud, local))
        assertSame(local, resolver.realizerFor(CapabilityId("ocr")))
    }

    @Test
    fun `unknown capability id throws`() {
        val resolver = DefaultResolver(setOf(realizer("ocr")))
        assertThrows(IllegalStateException::class.java) {
            resolver.realizerFor(CapabilityId("nope"))
        }
    }
}
