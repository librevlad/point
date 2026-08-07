package com.point.core.flow

import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(report.contains("scratch died"))
        assertTrue(report.contains("formatCrashReport") || report.contains("CrashReportTest"))
    }
}
