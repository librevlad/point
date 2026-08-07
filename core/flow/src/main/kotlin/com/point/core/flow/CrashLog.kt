package com.point.core.flow

import java.io.PrintWriter
import java.io.StringWriter

interface CrashLog {

    fun record(report: String)

    suspend fun pending(): String?

    suspend fun clear()
}

fun formatCrashReport(appVersion: String, threadName: String, error: Throwable): String {
    val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
    return buildString {
        appendLine("Point $appVersion — отчёт о падении")
        appendLine("Поток: $threadName")
        appendLine()
        append(stack)
    }
}
