package com.point.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predicate that replaced an assumption (#222).
 *
 * Every capability that reads, sends or saves bytes used to ask `kind != COLLECTION`, which
 * meant «any file» while the eight built-ins were the only kinds. Opening [ObjectKind] changed
 * that sentence's meaning silently — and unlike a non-exhaustive `when`, a `!=` compiles fine.
 * These tests are the guard the compiler cannot be.
 */
class FileBackedTest {

    @Test
    fun `the built-in kinds are bytes in scratch`() {
        listOf(
            ObjectKind.IMAGE, ObjectKind.TEXT, ObjectKind.PDF, ObjectKind.ZIP,
            ObjectKind.OFFICE, ObjectKind.URL, ObjectKind.AUDIO, ObjectKind.UNKNOWN,
        ).forEach { assertTrue("$it must be file-backed", it.isFileBacked) }
    }

    @Test
    fun `a collection is not — it holds children, not bytes`() {
        assertFalse(ObjectKind.COLLECTION.isFileBacked)
    }

    @Test
    fun `an extracted kind is not — its value IS its content`() {
        assertFalse(ObjectKind.of("Identifier").isFileBacked)
        assertFalse(ObjectKind.of("Address").isFileBacked)
        assertFalse(ObjectKind.of("Date").isFileBacked)
    }

    @Test
    fun `a kind minted tomorrow is not file-backed until someone decides it is`() {
        // The safe default: a new extractor cannot accidentally hand a value to `File(…)`.
        assertFalse(ObjectKind.of("Signature").isFileBacked)
    }
}
