package com.point.core.flow

import org.junit.Assert.assertTrue
import org.junit.Test

/** The crash report body (#11) — pure text, JVM-tested: what the owner receives by share. */
class CrashReportTest {

    @Test
    fun `report carries version, thread, exception chain and stack`() {
        val cause = IllegalStateException("scratch died")
        val error = RuntimeException("flow broke", cause)
        val report = formatCrashReport("0.2.0", "main", error)

        assertTrue(report.contains("Point 0.2.0"))
        assertTrue(report.contains("main"))
        assertTrue(report.contains("RuntimeException"))
        assertTrue(report.contains("flow broke"))
        assertTrue(report.contains("scratch died"))          // the cause is not lost
        assertTrue(report.contains("formatCrashReport") || report.contains("CrashReportTest")) // a real stack
    }
}
