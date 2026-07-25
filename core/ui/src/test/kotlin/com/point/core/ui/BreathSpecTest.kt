package com.point.core.ui

import com.point.core.model.ObjectKind
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every object kind has its own physics (MOTION.md, принцип №6) — pure data, JVM-tested. */
class BreathSpecTest {

    @Test
    fun `a photo breathes softer but wider than a strict document`() {
        val photo = breathSpecFor(ObjectKind.IMAGE)
        val document = breathSpecFor(ObjectKind.PDF)
        assertTrue("photo must move more than a document", photo.scale > document.scale)
        assertTrue("a document must be slower (stricter)", document.periodMs >= photo.periodMs)
    }

    @Test
    fun `every kind gets a sane, alive spec`() {
        ObjectKind.entries.forEach { kind ->
            val spec = breathSpecFor(kind)
            assertTrue("$kind must breathe at least a little", spec.scale > 1.0f)
            assertTrue("$kind must not wobble visibly", spec.scale <= 1.01f)
            assertTrue("$kind period must be calm (>= 3s)", spec.periodMs >= 3_000)
        }
    }
}
