package com.point.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FileCrashLogTest {

    private val file = File.createTempFile("crash", ".txt").apply { delete(); deleteOnExit() }
    private val log = FileCrashLog(file)

    @Test
    fun `record then pending round-trips, clear forgets`() = runTest {
        log.record("Point 0.2.0 crashed\nstack...")
        assertEquals("Point 0.2.0 crashed\nstack...", log.pending())

        log.clear()
        assertNull(log.pending())
    }

    @Test
    fun `no crash - nothing pending`() = runTest {
        assertNull(log.pending())
    }
}
