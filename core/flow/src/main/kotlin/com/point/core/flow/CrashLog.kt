package com.point.core.flow

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash visibility for a store release (#11) — privacy-first: NO third-party crash SDK.
 * The report is written to a local file at the moment of death and OFFERED to the user on
 * the next start; nothing ever leaves the device without an explicit share. That is the
 * whole mechanism — pluggable behind this seam if a heavier reporter is ever wanted.
 */
interface CrashLog {
    /** Called on the dying thread — must be synchronous and never throw. */
    fun record(report: String)

    /** The report of a previous crash, or null when the last run ended well. */
    suspend fun pending(): String?

    suspend fun clear()
}

/** The report body: version, thread, full stack with causes — pure and testable. */
fun formatCrashReport(appVersion: String, threadName: String, error: Throwable): String {
    val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    return buildString {
        appendLine("Point $appVersion — отчёт о падении")
        appendLine("Поток: $threadName")
        appendLine()
        append(stack)
    }
}
